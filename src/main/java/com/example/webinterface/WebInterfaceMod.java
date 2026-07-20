package com.example.webinterface;

import com.example.webinterface.command.WebInterfaceCommand;
import com.example.webinterface.config.ModConfig;
import com.example.webinterface.event.MinecraftEventListener;
import com.example.webinterface.model.ServerState;
import com.example.webinterface.web.WebServer;
import com.example.webinterface.web.auth.AuthManager;
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
 * WebInterface 模组主类
 * 在服务端启动时启动嵌入式 Netty HTTP+WebSocket 服务
 */
@Mod(WebInterfaceMod.MODID)
public class WebInterfaceMod {
    public static final String MODID = "webinterface";
    public static final Logger LOGGER = LogManager.getLogger();

    private static ModConfig config;
    private static WebServer webServer;
    private static AuthManager authManager;

    public WebInterfaceMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        // 注册游戏内事件监听器
        MinecraftForge.EVENT_BUS.register(new MinecraftEventListener());
        // 注册 /webinterface 命令
        MinecraftForge.EVENT_BUS.register(new WebInterfaceCommand());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        config = new ModConfig();
        LOGGER.info("[WebInterface] 配置已加载");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 在服务端启动时启动 Web 服务
        authManager = new AuthManager(config);
        webServer = new WebServer(config, authManager);
        webServer.start();
        LOGGER.info("[WebInterface] Web 服务已启动，端口: {}", config.getPort());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (webServer != null) {
            webServer.stop();
            LOGGER.info("[WebInterface] Web 服务已停止");
        }
        ServerState.invalidateAll();
    }

    public static ModConfig getConfig() {
        return config;
    }

    public static AuthManager getAuthManager() {
        return authManager;
    }

    public static WebServer getWebServer() {
        return webServer;
    }

    /** Services exposed to the /webinterface command. */
    public interface WebInterfaceServices {
        void reload();
        int getActiveSessionCount();
        int getCacheSize();
        boolean isWebServerRunning();
        int getPort();
        void invalidateCache();
    }

    public static WebInterfaceServices getServices() {
        if (config == null) return null;
        return new WebInterfaceServices() {
            @Override public void reload() {
                if (authManager != null) authManager.reload();
            }
            @Override public int getActiveSessionCount() {
                return authManager == null ? 0 : authManager.getActiveSessionCount();
            }
            @Override public int getCacheSize() {
                return ServerState.getCacheSize();
            }
            @Override public boolean isWebServerRunning() {
                return webServer != null && webServer.isRunning();
            }
            @Override public int getPort() {
                return config == null ? -1 : config.getPort();
            }
            @Override public void invalidateCache() {
                ServerState.invalidateAll();
            }
        };
    }
}
