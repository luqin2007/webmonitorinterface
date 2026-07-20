package com.example.webinterface.config;

import java.util.List;

/**
 * Subset of {@link ModConfig} used by {@link com.example.webinterface.security.MethodGuard}.
 * Both {@link ModConfig} and {@link ModConfig.MutableSnapshot} implement this so
 * that hot-applied permission overrides behave identically.
 */
public interface ModConfigLike {
    int getPort();
    String getBindAddress();
    boolean getAuthEnabled();
    boolean getAllowAnonymousRead();
    List<? extends String> getAuthUsers();
    List<? extends String> getBlacklist();
    List<? extends String> getWhitelist();
    int getDefaultPermLevel();
    int getTpsInterval();
    int getMaxRate();
    int getRateLimitPerMinute();
}
