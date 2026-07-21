package com.example.webinterface.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;

/**
 * TOML server config for Web Monitor Interface.
 */
public class ModConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue PORT = BUILDER
            .comment("Default HTTP/WS listen port")
            .defineInRange("server.port", 18080, 1024, 65535);

    private static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS = BUILDER
            .comment("Bind address. 127.0.0.1 = local only; 0.0.0.0 = all interfaces")
            .define("server.bind_address", "127.0.0.1");

    private static final ForgeConfigSpec.BooleanValue AUTO_START = BUILDER
            .comment("Automatically start the web server when the Minecraft server starts")
            .define("server.auto_start", true);

    private static final ForgeConfigSpec.IntValue REST_RATE_LIMIT = BUILDER
            .comment("Max REST requests per key per minute (token bucket). 0 = unlimited")
            .defineInRange("rest.rate_limit_per_minute", 120, 0, 100000);

    private static final ForgeConfigSpec.IntValue WS_TPS_INTERVAL = BUILDER
            .comment("TPS push interval in seconds (0 = disable)")
            .defineInRange("websocket.tps_update_interval", 5, 0, 300);

    private static final ForgeConfigSpec.IntValue WS_MAX_RATE = BUILDER
            .comment("Max WebSocket events per second per connection")
            .defineInRange("websocket.max_rate", 100, 1, 10000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public ModConfig() {
        ModLoadingContext.get().registerConfig(Type.SERVER, SPEC, "web_monitor_interface-server.toml");
    }

    public int getPort() { return PORT.get(); }
    public String getBindAddress() { return BIND_ADDRESS.get(); }
    public boolean isAutoStart() { return AUTO_START.get(); }
    public int getRateLimitPerMinute() { return REST_RATE_LIMIT.get(); }
    public int getTpsInterval() { return WS_TPS_INTERVAL.get(); }
    public int getMaxRate() { return WS_MAX_RATE.get(); }
}
