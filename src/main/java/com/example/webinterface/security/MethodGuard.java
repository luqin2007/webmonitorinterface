package com.example.webinterface.security;

import com.example.webinterface.config.ModConfigLike;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable, hot-reloadable regex policy for reflective and capability calls.
 *
 * <p>Decision flow (matches PLAN.md §六):
 * <ol>
 *   <li>level == 4 (op) → allow</li>
 *   <li>level == 0 (guest) → deny on any method (only public REST)</li>
 *   <li>match blacklist regex → deny</li>
 *   <li>match whitespace-default regex (full method or short name) → allow</li>
 *   <li>otherwise → deny</li>
 * </ol>
 */
public final class MethodGuard {

    private final AtomicReference<ConfigSnapshot> configRef = new AtomicReference<>();

    public MethodGuard(ModConfigLike config) {
        reload(config);
    }

    public void reload(ModConfigLike config) {
        configRef.set(new ConfigSnapshot(
                compile(config.getBlacklist(), "blacklist"),
                compile(config.getWhitelist(), "whitelist"),
                config.getDefaultPermLevel()
        ));
    }

    public CheckResult check(int level, String methodName) {
        if (methodName == null || methodName.isBlank()) return CheckResult.deny("method name is required");
        ConfigSnapshot config = configRef.get();
        int effectiveLevel = Math.max(0, Math.min(4, level));
        if (effectiveLevel == 4) return CheckResult.ALLOW;
        if (matches(config.blacklist, methodName)) return CheckResult.deny("blacklist rule matched");
        if (effectiveLevel == 0) return CheckResult.deny("guest cannot invoke methods");
        if (matches(config.whitelist, methodName) || matchesShortName(config.whitelist, methodName)) {
            return CheckResult.ALLOW;
        }
        return CheckResult.deny("no whitelist rule matched");
    }

    public int getDefaultLevel() { return configRef.get().defaultLevel; }

    public List<String> getBlacklist() {
        return configRef.get().blacklist.stream().map(Pattern::pattern).toList();
    }

    public List<String> getWhitelist() {
        return configRef.get().whitelist.stream().map(Pattern::pattern).toList();
    }

    private static List<Pattern> compile(List<? extends String> rules, String kind) {
        List<Pattern> patterns = new ArrayList<>();
        if (rules == null) return List.copyOf(patterns);
        for (String rule : rules) {
            try {
                patterns.add(Pattern.compile(rule));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid " + kind + " regex: " + rule, e);
            }
        }
        return List.copyOf(patterns);
    }

    private static boolean matches(List<Pattern> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).matches());
    }

    private static boolean matchesShortName(List<Pattern> patterns, String method) {
        int separator = method.lastIndexOf('.');
        return separator >= 0 && matches(patterns, method.substring(separator + 1));
    }

    public record ConfigSnapshot(List<Pattern> blacklist, List<Pattern> whitelist, int defaultLevel) {}

    public static final class CheckResult {
        public static final CheckResult ALLOW = new CheckResult(true, "allow");
        public final boolean allowed;
        public final String reason;
        private CheckResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
        public static CheckResult allow(String reason) { return new CheckResult(true, reason); }
        public static CheckResult deny(String reason) { return new CheckResult(false, reason); }
    }
}
