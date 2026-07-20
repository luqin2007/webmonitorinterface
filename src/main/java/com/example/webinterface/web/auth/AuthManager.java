package com.example.webinterface.web.auth;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.config.ModConfig;
import com.example.webinterface.config.ModConfigLike;
import com.example.webinterface.security.MethodGuard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auth/Session/Permission manager.
 *
 * <p>Holds:
 * <ul>
 *   <li>token -> Session map (concurrent)</li>
 *   <li>username -> credential snapshot (loadable from config, ready for hot-reload)</li>
 *   <li>AtomicReference to MethodGuard for hot-reload of method allow/deny rules</li>
 *   <li>Per-session rate-limit configuration capacity/rate</li>
 * </ul>
 *
 * <p>This is the single entry used by both {@link com.example.webinterface.web.api.AuthApi}
 * (REST) and the {@code /webinterface reload} command for configuration refresh.
 */
public final class AuthManager {

    /** Default rate-limit used when config has no override. */
    private static final int DEFAULT_RATE_PER_MIN = 120;

    private final ModConfig config;
    private final AtomicReference<MethodGuard> guardRef;
    private final AtomicReference<Credentials> credsRef;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthManager(ModConfig config) {
        this.config = config;
        this.guardRef = new AtomicReference<>(new MethodGuard(config));
        this.credsRef = new AtomicReference<>(Credentials.fromConfig(config));
    }

    // ============ Auth ============

    /**
     * Attempt login. On success, returns a new Session token.
     * Returns null if the username is unknown or the password is wrong.
     */
    public Session login(String username, String password) {
        if (username == null || username.isBlank() || password == null) return null;
        Credentials creds = credsRef.get();
        User user = creds.users.get(username);
        if (user == null) return null;
        if (!user.password.equals(password)) {
            WebInterfaceMod.LOGGER.warn("[Auth] Login failed for user '{}'", username);
            return null;
        }
        Session session = new Session(username, user.level, rateLimitCapacity(), rateLimitPerSec());
        sessions.put(session.getToken(), session);
        WebInterfaceMod.LOGGER.info("[Auth] User '{}' logged in (level={}, token={}...)", username, user.level, session.getToken().substring(0, 8));
        return session;
    }

    /** Logout by session token. Returns true if a session was removed. */
    public boolean logout(String token) {
        if (token == null) return false;
        Session removed = sessions.remove(token);
        return removed != null;
    }

    public Optional<Session> getSession(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Session session = sessions.get(token);
        if (session == null) return Optional.empty();
        session.touch();
        return Optional.of(session);
    }

    /** Anonymous user default level (level=0 unless config bumps it). */
    public int getAnonymousLevel() {
        return 0;
    }

    public double rateLimitCapacity() {
        int perMin = config.getRateLimitPerMinute();
        if (perMin <= 0) perMin = DEFAULT_RATE_PER_MIN;
        return perMin;
    }

    public double rateLimitPerSec() {
        return rateLimitCapacity() / 60.0;
    }

    public MethodGuard getMethodGuard() { return guardRef.get(); }

    // ============ Hot reload ============

    /**
     * Reload MethodGuard + user credentials from config (hot reload).
     * Active sessions are kept; existing tokens remain valid (their permission
     * level is preserved on the Session but new method calls will be checked
     * against the new guard rules).
     */
    public void reload() {
        guardRef.set(new MethodGuard(config));
        credsRef.set(Credentials.fromConfig(config));
        WebInterfaceMod.LOGGER.info("[Auth] Configuration reloaded: {} users configured, default_level={}",
                credsRef.get().users.size(), config.getDefaultPermLevel());
    }

    /**
     * Reload MethodGuard with the provided rules (overrides config).
     * Used by PUT /api/v1/auth/permissions to apply new rules immediately.
     */
    public void applyMethodRules(List<String> blacklist, List<String> whitelistDefault, Integer defaultLevel) {
        List<String> finalBlacklist = blacklist != null
                ? new ArrayList<>(blacklist)
                : new ArrayList<>(config.getBlacklist());
        List<String> finalWhitelist = whitelistDefault != null
                ? new ArrayList<>(whitelistDefault)
                : new ArrayList<>(config.getWhitelist());
        int finalDefault = defaultLevel != null ? defaultLevel : config.getDefaultPermLevel();
        ModConfig.MutableSnapshot snapshot = new ModConfig.MutableSnapshot(
                config.getPort(),
                config.getBindAddress(),
                finalBlacklist,
                finalWhitelist,
                finalDefault,
                config.getTpsInterval(),
                config.getMaxRate(),
                config.getRateLimitPerMinute(),
                config.getAuthEnabled(),
                config.getAllowAnonymousRead()
        );
        guardRef.set(new MethodGuard(snapshot));
    }

    /** Number of active sessions (monitoring/stat endpoint). */
    public int getActiveSessionCount() { return sessions.size(); }

    /** Snapshot user list for /api/v1/auth/me and management endpoints. */
    public JsonArray listUsers() {
        JsonArray arr = new JsonArray();
        for (User user : credsRef.get().users.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("username", user.username);
            o.addProperty("level", user.level);
            arr.add(o);
        }
        return arr;
    }

    public List<? extends String> getBlacklist() { return config.getBlacklist(); }
    public List<? extends String> getWhitelist() { return config.getWhitelist(); }
    public int getDefaultLevel() { return config.getDefaultPermLevel(); }
    public boolean isAuthEnabled() { return config.getAuthEnabled(); }
    public boolean isAllowAnonymousRead() { return config.getAllowAnonymousRead(); }
    public ModConfig getConfig() { return config; }

    // ============ Internals ============

    private static final class Credentials {
        final Map<String, User> users = new HashMap<>();
        static Credentials fromConfig(ModConfig config) {
            Credentials c = new Credentials();
            for (String entry : config.getAuthUsers()) {
                User user = User.parse(entry);
                if (user != null) c.users.put(user.username, user);
            }
            return c;
        }
    }

    public static final class User {
        final String username;
        final String password;
        final int level;
        User(String username, String password, int level) {
            this.username = username;
            this.password = password;
            this.level = Math.max(0, Math.min(4, level));
        }
        static User parse(String entry) {
            if (entry == null) return null;
            String[] parts = entry.split(":", 3);
            if (parts.length < 2) return null;
            int level = parts.length == 3 ? parseInt(parts[2], 2) : 2;
            return new User(parts[0], parts[1], level);
        }
        private static int parseInt(String s, int d) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return d; }
        }
    }
}
