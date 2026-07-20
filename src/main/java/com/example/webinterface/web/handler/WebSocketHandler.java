package com.example.webinterface.web.handler;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.web.auth.AuthManager;
import com.example.webinterface.web.auth.Session;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Per-channel wildcard subscriptions for event delivery. */
public final class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Gson GSON = new Gson();
    private static final Set<Channel> ACTIVE_CHANNELS = new CopyOnWriteArraySet<>();
    private static final ConcurrentHashMap<Channel, Set<String>> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Channel, Session> SESSIONS = new ConcurrentHashMap<>();

    private final AuthManager authManager;

    public WebSocketHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ACTIVE_CHANNELS.add(ctx.channel());
        SUBSCRIPTIONS.put(ctx.channel(), ConcurrentHashMap.newKeySet());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ACTIVE_CHANNELS.remove(channel);
        SUBSCRIPTIONS.remove(channel);
        SESSIONS.remove(channel);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (frame instanceof CloseWebSocketFrame) { ctx.close(); return; }
        if (frame instanceof TextWebSocketFrame text) handle(ctx, text.text());
    }

    private void handle(ChannelHandlerContext ctx, String text) {
        Session session = SESSIONS.get(ctx.channel());
        try {
            JsonObject request = GSON.fromJson(text, JsonObject.class);
            if (request == null) { reply(ctx, "ack", false, "Invalid JSON message"); return; }
            String action = request.has("action") ? request.get("action").getAsString() : "";
            // Per-message auth check for sensitive actions
            if ("login".equals(action)) {
                handleLogin(ctx, request);
                return;
            }
            switch (action) {
                case "subscribe" -> update(ctx, request, true);
                case "unsubscribe" -> update(ctx, request, false);
                case "ping" -> reply(ctx, "pong", true, (JsonObject) null);
                case "publish" -> {
                    if (session == null) { reply(ctx, "ack", false, "Publish requires authentication"); return; }
                    String event = request.has("event") ? request.get("event").getAsString() : "";
                    if (!event.startsWith("custom:")) { reply(ctx, "ack", false, "Only custom: events may be published"); return; }
                    JsonObject data = request.has("data") && request.get("data").isJsonObject()
                            ? request.getAsJsonObject("data") : new JsonObject();
                    broadcast(GSON.toJson(eventPayload(event, data)));
                    reply(ctx, "ack", true, (JsonObject) null);
                }
                case "whoami" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("authenticated", session != null);
                    if (session != null) {
                        data.addProperty("username", session.getUsername());
                        data.addProperty("level", session.getLevel());
                    }
                    reply(ctx, "ack", true, data);
                }
                default -> reply(ctx, "ack", false, "Unknown action: " + action);
            }
        } catch (Exception e) {
            reply(ctx, "ack", false, "Invalid message: " + e.getMessage());
        }
    }

    private void handleLogin(ChannelHandlerContext ctx, JsonObject request) {
        if (authManager == null) { reply(ctx, "ack", false, "Auth subsystem not initialized"); return; }
        if (!authManager.isAuthEnabled()) { reply(ctx, "ack", false, "Auth disabled on this server"); return; }
        String username = request.has("username") ? request.get("username").getAsString() : "";
        String password = request.has("password") ? request.get("password").getAsString() : "";
        if (username.isBlank() || password.isBlank()) { reply(ctx, "ack", false, "username and password are required"); return; }
        Session session = authManager.login(username, password);
        if (session == null) { reply(ctx, "ack", false, "Invalid credentials"); return; }
        SESSIONS.put(ctx.channel(), session);
        JsonObject data = new JsonObject();
        data.addProperty("token", session.getToken());
        data.addProperty("username", session.getUsername());
        data.addProperty("level", session.getLevel());
        reply(ctx, "ack", true, data);
    }

    private void update(ChannelHandlerContext ctx, JsonObject request, boolean add) {
        JsonArray events = request.has("events") && request.get("events").isJsonArray()
                ? request.getAsJsonArray("events") : new JsonArray();
        if (request.has("event")) events.add(request.get("event"));
        if (events.isEmpty()) { reply(ctx, "ack", false, "event or events is required"); return; }
        Set<String> subscriptions = SUBSCRIPTIONS.get(ctx.channel());
        if (subscriptions == null) { reply(ctx, "ack", false, "channel closed"); return; }
        for (JsonElement element : events) {
            String filter = element.getAsString();
            if (!validFilter(filter)) { reply(ctx, "ack", false, "Invalid event filter: " + filter); return; }
            if (add) subscriptions.add(filter);
            else subscriptions.remove(filter);
        }
        JsonObject data = new JsonObject();
        JsonArray values = new JsonArray();
        subscriptions.forEach(values::add);
        data.add("subscriptions", values);
        data.addProperty("session", SESSIONS.get(ctx.channel()) != null);
        reply(ctx, "ack", true, data);
    }

    private static boolean validFilter(String filter) {
        if (filter == null || filter.isBlank()) return false;
        try { Pattern.compile(Pattern.quote(filter).replace("*", "\\E.*\\Q")); return true; }
        catch (PatternSyntaxException e) { return false; }
    }

    public static void broadcast(String jsonEvent) {
        try {
            JsonObject event = GSON.fromJson(jsonEvent, JsonObject.class);
            String name = event.has("event") ? event.get("event").getAsString() : "";
            int tick = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() == null ? 0
                    : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
            for (Channel channel : ACTIVE_CHANNELS) {
                if (channel.isActive() && matches(channel, name)) {
                    channel.writeAndFlush(new TextWebSocketFrame(jsonEvent));
                }
            }
        } catch (Exception e) {
            WebInterfaceMod.LOGGER.warn("[WS] Refused malformed event payload", e);
        }
    }

    private static boolean matches(Channel channel, String event) {
        Set<String> filters = SUBSCRIPTIONS.get(channel);
        return filters != null && filters.stream().anyMatch(filter ->
                event.matches(Pattern.quote(filter).replace("*", "\\E.*\\Q")));
    }

    private static JsonObject eventPayload(String event, JsonObject data) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "event");
        p.addProperty("event", event);
        p.addProperty("ts", System.currentTimeMillis());
        int tick = ServerLifecycleHooks.getCurrentServer() == null ? 0
                : ServerLifecycleHooks.getCurrentServer().getTickCount();
        p.addProperty("server_tick", tick);
        p.add("data", data);
        return p;
    }

    private static void reply(ChannelHandlerContext ctx, String type, boolean ok, JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("type", type);
        response.addProperty("ok", ok);
        if (data != null) response.add("data", data);
        ctx.channel().writeAndFlush(new TextWebSocketFrame(GSON.toJson(response)));
    }

    private static void reply(ChannelHandlerContext ctx, String type, boolean ok, String error) {
        JsonObject response = new JsonObject();
        response.addProperty("type", type);
        response.addProperty("ok", ok);
        if (error != null) response.addProperty("error", error);
        ctx.channel().writeAndFlush(new TextWebSocketFrame(GSON.toJson(response)));
    }

    public static int getActiveConnections() { return ACTIVE_CHANNELS.size(); }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        WebInterfaceMod.LOGGER.error("[WS] Exception", cause);
        ctx.close();
    }
}
