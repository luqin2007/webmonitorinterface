package com.example.webinterface.web.auth;

import com.example.webinterface.security.MethodGuard;

import java.util.UUID;

/**
 * Authenticated session: holds the user identity, fixed permission level
 * and an independent rate-limit bucket.
 *
 * <p>Levels (same as PLAN.md):
 * <ul>
 *   <li>4 op - all methods allowed</li>
 *   <li>3 operator - whitelist + role_overrides exemption</li>
 *   <li>2 normal - whitelist_default only</li>
 *   <li>1 restricted - explicit allowed list</li>
 *   <li>0 guest - read-only public API only</li>
 * </ul>
 */
public final class Session {
    private final String token;
    private final String username;
    private final int level;
    private final long createdAt;
    private volatile long lastAccessAt;
    private final TokenBucket bucket;

    public Session(String username, int level, double rateCapacity, double ratePerSec) {
        this.token = UUID.randomUUID().toString().replace("-", "");
        this.username = username;
        this.level = Math.max(0, Math.min(4, level));
        this.createdAt = System.currentTimeMillis();
        this.lastAccessAt = this.createdAt;
        this.bucket = new TokenBucket(rateCapacity, ratePerSec);
    }

    public String getToken() { return token; }
    public String getUsername() { return username; }
    public int getLevel() { return level; }
    public long getCreatedAt() { return createdAt; }
    public long getLastAccessAt() { return lastAccessAt; }
    public TokenBucket getBucket() { return bucket; }

    public boolean isOp() { return level >= 4; }
    public boolean canManagePermissions() { return level >= 4; }

    /** Update last-access timestamp. Called once per authenticated request. */
    public void touch() { this.lastAccessAt = System.currentTimeMillis(); }

    /** Try to consume one rate-limit token. */
    public boolean tryRateLimit() { return bucket.tryAcquire(); }

    /** Check method invocation permission via MethodGuard for this session level. */
    public MethodGuard.CheckResult checkMethod(MethodGuard guard, String method) {
        return guard.check(level, method);
    }
}
