package com.example.webinterface.web.api;

import com.example.webinterface.config.ModConfig;
import com.example.webinterface.security.MethodGuard;
import com.example.webinterface.web.auth.AuthManager;
import com.example.webinterface.web.auth.Session;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Auth & permissions REST API - the unified /api/v1/auth namespace.
 *
 * <p>Endpoints (all under /api/v1/auth):
 * <ul>
 *   <li>POST /login            - body {username, password} -> session token</li>
 *   <li>POST /logout           - header Bearer token -> invalidate session</li>
 *   <li>GET  /me               - current session info + level</li>
 *   <li>GET  /methods          - effective method blacklist/whitelist rules</li>
 *   <li>GET  /permissions      - full permissions config snapshot (op)</li>
 *   <li>PUT  /permissions      - update method rules + default_level (op)</li>
 *   <li>POST /refresh          - refresh current session token</li>
 * </ul>
 */
public final class AuthApi {

    private AuthApi() {}

    /** POST /api/v1/auth/login */
    public static JsonObject login(AuthManager auth, JsonObject body) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        if (!auth.isAuthEnabled()) return error(2001, "Auth is disabled on this server");
        String username = string(body, "username", null);
        String password = string(body, "password", null);
        if (username == null || username.isBlank()) return error(1001, "username is required");
        if (password == null) return error(1001, "password is required");
        Session session = auth.login(username, password);
        if (session == null) return error(2001, "Invalid credentials");
        JsonObject data = new JsonObject();
        data.addProperty("token", session.getToken());
        data.addProperty("username", session.getUsername());
        data.addProperty("level", session.getLevel());
        data.addProperty("expires_in", 0);
        JsonObject rate = new JsonObject();
        rate.addProperty("capacity", session.getBucket().getCapacity());
        rate.addProperty("rate_per_sec", session.getBucket().getRatePerSec());
        data.add("rate_limit", rate);
        return ok(data);
    }

    /** POST /api/v1/auth/logout */
    public static JsonObject logout(AuthManager auth, String token) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        if (token == null || token.isBlank()) return error(1001, "session token is required");
        boolean removed = auth.logout(token);
        JsonObject data = new JsonObject();
        data.addProperty("logged_out", removed);
        return ok(data);
    }

    /** GET /api/v1/auth/me */
    public static JsonObject me(AuthManager auth, Optional<Session> session) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        JsonObject data = new JsonObject();
        if (session.isPresent()) {
            Session s = session.get();
            data.addProperty("authenticated", true);
            data.addProperty("username", s.getUsername());
            data.addProperty("level", s.getLevel());
            data.addProperty("token_prefix", s.getToken().substring(0, Math.min(8, s.getToken().length())));
            data.addProperty("created_at", s.getCreatedAt());
            data.addProperty("last_access_at", s.getLastAccessAt());
            JsonObject rate = new JsonObject();
            rate.addProperty("available_tokens", s.getBucket().getAvailableTokens());
            rate.addProperty("capacity", s.getBucket().getCapacity());
            data.add("rate_limit", rate);
        } else {
            data.addProperty("authenticated", false);
            data.addProperty("level", auth.getAnonymousLevel());
            data.addProperty("reason", "no token provided");
        }
        data.addProperty("auth_enabled", auth.isAuthEnabled());
        data.addProperty("allow_anonymous_read", auth.isAllowAnonymousRead());
        data.addProperty("default_level", auth.getDefaultLevel());
        data.addProperty("active_sessions", auth.getActiveSessionCount());
        return ok(data);
    }

    /** GET /api/v1/auth/methods - list effective allow/deny rules. */
    public static JsonObject methods(AuthManager auth, Optional<Session> session) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        MethodGuard guard = auth.getMethodGuard();
        JsonObject data = new JsonObject();
        data.addProperty("default_level", guard.getDefaultLevel());
        JsonArray blacklist = new JsonArray();
        for (String rule : guard.getBlacklist()) blacklist.add(rule);
        JsonArray whitelist = new JsonArray();
        for (String rule : guard.getWhitelist()) whitelist.add(rule);
        data.add("blacklist", blacklist);
        data.add("whitelist", whitelist);
        data.add("users", auth.listUsers());
        if (session.isPresent()) {
            data.addProperty("current_level", session.get().getLevel());
        }
        return ok(data);
    }

    /** GET /api/v1/auth/permissions - full permission config (op only). */
    public static JsonObject viewPermissions(AuthManager auth, Optional<Session> session) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        if (session.isEmpty() || !session.get().canManagePermissions()) return error(2003, "op level required");
        ModConfig config = auth.getConfig();
        JsonObject data = new JsonObject();
        JsonObject server = new JsonObject();
        server.addProperty("port", config.getPort());
        server.addProperty("bind_address", config.getBindAddress());
        data.add("server", server);
        JsonObject authObj = new JsonObject();
        authObj.addProperty("enabled", config.getAuthEnabled());
        authObj.addProperty("allow_anonymous_read", config.getAllowAnonymousRead());
        JsonArray users = new JsonArray();
        for (String u : config.getAuthUsers()) users.add(u);
        authObj.add("users", users);
        data.add("auth", authObj);
        JsonObject perms = new JsonObject();
        perms.addProperty("default_level", config.getDefaultPermLevel());
        JsonArray bl = new JsonArray();
        for (String r : config.getBlacklist()) bl.add(r);
        perms.add("blacklist", bl);
        JsonArray wl = new JsonArray();
        for (String r : config.getWhitelist()) wl.add(r);
        perms.add("whitelist_default", wl);
        data.add("permissions", perms);
        JsonObject ws = new JsonObject();
        ws.addProperty("tps_update_interval", config.getTpsInterval());
        ws.addProperty("max_rate", config.getMaxRate());
        data.add("websocket", ws);
        JsonObject rl = new JsonObject();
        rl.addProperty("rate_limit_per_minute", config.getRateLimitPerMinute());
        data.add("rest", rl);
        return ok(data);
    }

    /** PUT /api/v1/auth/permissions - update method rules + default_level (op only). */
    public static JsonObject updatePermissions(AuthManager auth, Optional<Session> session, JsonObject body) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        if (session.isEmpty() || !session.get().canManagePermissions()) return error(2003, "op level required");

        List<String> newBlacklist = null, newWhitelist = null;
        Integer newDefaultLevel = null;
        if (body.has("permissions")) {
            JsonObject perms = body.getAsJsonObject("permissions");
            if (perms.has("blacklist") && perms.get("blacklist").isJsonArray()) {
                newBlacklist = new ArrayList<>();
                for (JsonElement e : perms.getAsJsonArray("blacklist")) newBlacklist.add(e.getAsString());
            }
            if (perms.has("whitelist_default") && perms.get("whitelist_default").isJsonArray()) {
                newWhitelist = new ArrayList<>();
                for (JsonElement e : perms.getAsJsonArray("whitelist_default")) newWhitelist.add(e.getAsString());
            }
            if (perms.has("default_level")) {
                int lvl = perms.get("default_level").getAsInt();
                if (lvl < 0 || lvl > 4) return error(1001, "default_level must be 0..4");
                newDefaultLevel = lvl;
            }
        }
        // Validate regex compile
        try {
            auth.applyMethodRules(newBlacklist, newWhitelist, newDefaultLevel);
        } catch (IllegalArgumentException e) {
            return error(1001, e.getMessage());
        }

        JsonObject data = new JsonObject();
        data.addProperty("updated", true);
        data.addProperty("note", "Changes apply in-memory; restart or /webinterface reload persists them.");
        if (newBlacklist != null) data.addProperty("blacklist_size", newBlacklist.size());
        if (newWhitelist != null) data.addProperty("whitelist_size", newWhitelist.size());
        if (newDefaultLevel != null) data.addProperty("default_level", newDefaultLevel);
        return ok(data);
    }

    /** POST /api/v1/auth/refresh - mint a fresh token for the current session. */
    public static JsonObject refresh(AuthManager auth, Optional<Session> session) {
        if (auth == null) return error(2001, "Auth subsystem is not initialized");
        if (session.isEmpty()) return error(2001, "Not authenticated");
        // Touch session - keep same token (refresh semantics = extend activity).
        Session s = session.get();
        s.touch();
        JsonObject data = new JsonObject();
        data.addProperty("token", s.getToken());
        data.addProperty("username", s.getUsername());
        data.addProperty("refreshed", true);
        return ok(data);
    }

    // ============ helpers ============
    private static String string(JsonObject o, String key, String fallback) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }
    public static JsonObject ok(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 0);
        o.addProperty("msg", "ok");
        o.add("data", data);
        return o;
    }
    public static JsonObject error(int code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("msg", msg);
        return o;
    }
}
