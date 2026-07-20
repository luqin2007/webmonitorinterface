package com.example.webinterface;

import com.example.webinterface.command.WebMonitorCommand;
import com.example.webinterface.config.ModConfig;
import com.example.webinterface.event.MinecraftEventListener;
import com.example.webinterface.model.ServerState;
import com.example.webinterface.security.KeyManager;
import com.example.webinterface.web.WebServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Web Monitor Interface - HTTP + WebSocket monitoring API for Minecraft servers.
 * Players authenticate with API keys created via {@code /webmonitor key generate}.
 */
@Mod(WebMonitorMod.MODID)
public class WebMonitorMod {
    public static final String MODID = "web_monitor_interface";
    public static final Logger LOGGER = LogManager.getLogger();

    private static ModConfig config;
    private static KeyManager keyManager;
    private static WebServer webServer;

    public WebMonitorMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new MinecraftEventListener());
        MinecraftForge.EVENT_BUS.register(new WebMonitorCommand());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        config = new ModConfig();
        LOGGER.info("[WebMonitor] config registered");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        keyManager = new KeyManager();
        webServer = new WebServer(config, keyManager);
        if (config.isAutoStart()) {
            webServer.start(config.getPort());
            LOGGER.info("[WebMonitor] web server auto-started on {}:{}", config.getBindAddress(), config.getPort());
        } else {
            LOGGER.info("[WebMonitor] auto_start=false; use /webmonitor server start");
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (webServer != null) {
            webServer.stop();
            LOGGER.info("[WebMonitor] web server stopped");
        }
        ServerState.invalidateAll();
    }

    public static ModConfig getConfig() { return config; }
    public static KeyManager getKeyManager() { return keyManager; }
    public static WebServer getWebServer() { return webServer; }

    /** Ensure web server instance exists (for commands before auto-start). */
    public static WebServer ensureWebServer() {
        if (webServer == null && config != null && keyManager != null) {
            webServer = new WebServer(config, keyManager);
        }
        return webServer;
    }
}
