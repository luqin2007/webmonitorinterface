package com.example.webinterface.web.handler;

import com.example.webinterface.security.ApiKey;
import com.example.webinterface.security.KeyManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;

import java.util.List;
import java.util.Optional;

/** Validates WebSocket upgrade requests with the same API-key inputs as REST. */
public final class WebSocketAuthHandler extends ChannelInboundHandlerAdapter {
    static final AttributeKey<ApiKey> API_KEY = AttributeKey.valueOf("web_monitor_api_key");

    private final KeyManager keyManager;

    public WebSocketAuthHandler(KeyManager keyManager) {
        this.keyManager = keyManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (!(message instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(message);
            return;
        }
        QueryStringDecoder query = new QueryStringDecoder(request.uri());
        if (!"/ws".equals(query.path())) {
            ctx.fireChannelRead(message);
            return;
        }
        Optional<ApiKey> key = resolveKey(request, query);
        if (keyManager != null && keyManager.isAuthRequired() && key.isEmpty()) {
            sendUnauthorized(ctx);
            request.release();
            return;
        }
        key.ifPresent(value -> ctx.channel().attr(API_KEY).set(value));
        ctx.fireChannelRead(message);
    }

    private Optional<ApiKey> resolveKey(FullHttpRequest request, QueryStringDecoder query) {
        if (keyManager == null) return Optional.empty();
        String key = null;
        String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) key = authorization.substring(7).trim();
        if (key == null || key.isBlank()) key = request.headers().get("X-Api-Key");
        if (key == null || key.isBlank()) key = request.headers().get("X-Auth-Token");
        if (key == null || key.isBlank()) key = parameter(query, "key");
        if (key == null || key.isBlank()) key = parameter(query, "token");
        return keyManager.get(key);
    }

    private static String parameter(QueryStringDecoder query, String name) {
        List<String> values = query.parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void sendUnauthorized(ChannelHandlerContext ctx) {
        String body = "{\"code\":2001,\"msg\":\"API key required\"}";
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
