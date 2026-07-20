package com.example.webinterface.web;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.config.ModConfig;
import com.example.webinterface.web.auth.AuthManager;
import com.example.webinterface.web.handler.RestHandler;
import com.example.webinterface.web.handler.WebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * 嵌入式 Netty HTTP + WebSocket 服务器
 * 复用 Forge 自带的 Netty 依赖，无需额外引入
 */
public class WebServer {

    private final ModConfig config;
    private final AuthManager authManager;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;
    private volatile boolean running = false;

    public WebServer(ModConfig config, AuthManager authManager) {
        this.config = config;
        this.authManager = authManager;
    }

    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(65536));
                            pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                            pipeline.addLast(new RestHandler(authManager));
                            pipeline.addLast(new WebSocketHandler(authManager));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            channelFuture = bootstrap.bind(config.getBindAddress(), config.getPort()).sync();
            running = true;

            WebInterfaceMod.LOGGER.info("[WebServer] 监听 {}:{}", config.getBindAddress(), config.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            WebInterfaceMod.LOGGER.error("[WebServer] 启动失败", e);
        }
    }

    public void stop() {
        running = false;
        if (channelFuture != null) {
            channelFuture.channel().close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        WebInterfaceMod.LOGGER.info("[WebServer] 已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }
}
