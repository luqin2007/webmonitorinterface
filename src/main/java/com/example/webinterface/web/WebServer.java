package com.example.webinterface.web;

import com.example.webinterface.WebMonitorMod;
import com.example.webinterface.config.ModConfig;
import com.example.webinterface.security.KeyManager;
import com.example.webinterface.web.handler.RestHandler;
import com.example.webinterface.web.handler.WebSocketAuthHandler;
import com.example.webinterface.web.handler.WebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Embedded Netty HTTP + WebSocket server.
 * Supports start/stop/restart on a chosen port.
 */
public class WebServer {

    private final ModConfig config;
    private final KeyManager keyManager;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;
    private volatile boolean running = false;
    private volatile int boundPort = -1;

    public WebServer(ModConfig config, KeyManager keyManager) {
        this.config = config;
        this.keyManager = keyManager;
    }

    /**
     * Start (or restart) on the given port.
     * @return true if bind succeeded
     */
    public synchronized boolean start(int port) {
        if (running) {
            stop();
        }
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
                            pipeline.addLast(new WebSocketAuthHandler(keyManager));
                            pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                            pipeline.addLast(new RestHandler(keyManager));
                            pipeline.addLast(new WebSocketHandler(keyManager));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            String bind = config == null ? "127.0.0.1" : config.getBindAddress();
            channelFuture = bootstrap.bind(bind, port).sync();
            running = true;
            boundPort = port;
            WebMonitorMod.LOGGER.info("[WebServer] listening on {}:{}", bind, port);
            return true;
        } catch (Exception e) {
            WebMonitorMod.LOGGER.error("[WebServer] failed to start on port {}", port, e);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        boundPort = -1;
        if (channelFuture != null) {
            try { channelFuture.channel().close().syncUninterruptibly(); } catch (Exception ignored) {}
            channelFuture = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        WebMonitorMod.LOGGER.info("[WebServer] stopped");
    }

    public boolean isRunning() { return running; }
    public int getBoundPort() { return boundPort; }
    public KeyManager getKeyManager() { return keyManager; }
}
