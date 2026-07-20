package com.example.webinterface.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 模组 TOML 配置管理
 * 配置项：端口、绑定地址、权限黑/白名单、认证用户、热重载令牌桶等。
 */
public class ModConfig implements ModConfigLike {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ====== 服务器配置 ======
    private static final ForgeConfigSpec.IntValue PORT = BUILDER
            .comment("Web 服务监听端口")
            .defineInRange("server.port", 8080, 1024, 65535);

    private static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS = BUILDER
            .comment("绑定地址，默认 127.0.0.1（仅本机），设为 0.0.0.0 允许外部访问")
            .define("server.bind_address", "127.0.0.1");

    // ====== 认证配置 ======
    private static final ForgeConfigSpec.BooleanValue AUTH_ENABLED = BUILDER
            .comment("是否启用认证（关闭时所有匿名请求使用 default_level）")
            .define("auth.enabled", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_ANONYMOUS_READ = BUILDER
            .comment("是否允许匿名（未登录）只读访问 REST API")
            .define("auth.allow_anonymous_read", true);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> AUTH_USERS = BUILDER
            .comment("认证用户列表，格式 username:password:level，level 0-4。例如 admin:secret:4")
            .defineList("auth.users", Arrays.asList("admin:admin:4"),
                    s -> s instanceof String);

    // ====== 权限配置 ======
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> PERM_BLACKLIST = BUILDER
            .comment("方法黑名单（正则），匹配的方法禁止调用，如 set*")
            .defineList("permissions.blacklist", Arrays.asList("set.*", "executeCommand", "shutdown.*"),
                    s -> s instanceof String);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> PERM_WHITELIST = BUILDER
            .comment("方法白名单（正则），默认 get[A-Z]*、is*、has* 可用")
            .defineList("permissions.whitelist_default", Arrays.asList("get[A-Z].*", "is[A-Z].*", "has[A-Z].*"),
                    s -> s instanceof String);

    private static final ForgeConfigSpec.IntValue DEFAULT_PERM_LEVEL = BUILDER
            .comment("默认权限等级：0=guest, 1=restricted, 2=normal, 3=operator, 4=op")
            .defineInRange("permissions.default_level", 2, 0, 4);

    // ====== WebSocket 配置 ======
    private static final ForgeConfigSpec.IntValue WS_TPS_INTERVAL = BUILDER
            .comment("TPS 定时推送间隔（秒），0 表示不推送")
            .defineInRange("websocket.tps_update_interval", 5, 0, 300);

    private static final ForgeConfigSpec.IntValue WS_RATE_LIMIT = BUILDER
            .comment("WebSocket 每秒最大事件数")
            .defineInRange("websocket.max_rate", 100, 1, 10000);

    // ====== REST 限流 ======
    private static final ForgeConfigSpec.IntValue REST_RATE_LIMIT = BUILDER
            .comment("REST API 每会话每分钟最大请求数（令牌桶），0 表示使用默认 120")
            .defineInRange("rest.rate_limit_per_minute", 120, 0, 100000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public ModConfig() {
        ModLoadingContext.get().registerConfig(Type.SERVER, SPEC, "webinterface-server.toml");
    }

    @Override public int getPort() { return PORT.get(); }
    @Override public String getBindAddress() { return BIND_ADDRESS.get(); }
    @Override public boolean getAuthEnabled() { return AUTH_ENABLED.get(); }
    @Override public boolean getAllowAnonymousRead() { return ALLOW_ANONYMOUS_READ.get(); }
    @Override public List<? extends String> getAuthUsers() { return AUTH_USERS.get(); }
    @Override public List<? extends String> getBlacklist() { return PERM_BLACKLIST.get(); }
    @Override public List<? extends String> getWhitelist() { return PERM_WHITELIST.get(); }
    @Override public int getDefaultPermLevel() { return DEFAULT_PERM_LEVEL.get(); }
    @Override public int getTpsInterval() { return WS_TPS_INTERVAL.get(); }
    @Override public int getMaxRate() { return WS_RATE_LIMIT.get(); }
    @Override public int getRateLimitPerMinute() { return REST_RATE_LIMIT.get(); }

    /** 可变快照，用于 PUT /auth/permissions 后构造一个替代的 ModConfig-like 视图。 */
    public static final class MutableSnapshot implements ModConfigLike {
        private final int port;
        private final String bindAddress;
        private final List<String> blacklist;
        private final List<String> whitelist;
        private final int defaultLevel;
        private final int tpsInterval;
        private final int maxRate;
        private final int rateLimitPerMinute;
        private final boolean authEnabled;
        private final boolean allowAnonymousRead;

        public MutableSnapshot(int port, String bindAddress, List<String> blacklist, List<String> whitelist,
                               int defaultLevel, int tpsInterval, int maxRate, int rateLimitPerMinute,
                               boolean authEnabled, boolean allowAnonymousRead) {
            this.port = port;
            this.bindAddress = bindAddress;
            this.blacklist = blacklist;
            this.whitelist = whitelist;
            this.defaultLevel = defaultLevel;
            this.tpsInterval = tpsInterval;
            this.maxRate = maxRate;
            this.rateLimitPerMinute = rateLimitPerMinute;
            this.authEnabled = authEnabled;
            this.allowAnonymousRead = allowAnonymousRead;
        }

        @Override public int getPort() { return port; }
        @Override public String getBindAddress() { return bindAddress; }
        @Override public boolean getAuthEnabled() { return authEnabled; }
        @Override public boolean getAllowAnonymousRead() { return allowAnonymousRead; }
        @Override public List<? extends String> getAuthUsers() { return new ArrayList<>(); }
        @Override public List<? extends String> getBlacklist() { return blacklist; }
        @Override public List<? extends String> getWhitelist() { return whitelist; }
        @Override public int getDefaultPermLevel() { return defaultLevel; }
        @Override public int getTpsInterval() { return tpsInterval; }
        @Override public int getMaxRate() { return maxRate; }
        @Override public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    }
}
