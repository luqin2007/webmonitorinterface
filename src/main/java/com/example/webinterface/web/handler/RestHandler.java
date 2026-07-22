package com.example.webinterface.web.handler;

import com.example.webinterface.WebMonitorMod;
import com.example.webinterface.model.ServerState;
import com.example.webinterface.security.ApiKey;
import com.example.webinterface.security.KeyManager;
import com.example.webinterface.web.api.BatchApi;
import com.example.webinterface.web.api.BlockApi;
import com.example.webinterface.web.api.CapabilityApi;
import com.example.webinterface.web.api.EntityApi;
import com.example.webinterface.web.MinecraftThread;
import com.example.webinterface.web.event.events.EventCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST v1 routing. Auth is API-key based:
 * <ul>
 *   <li>Integrated / singleplayer: no key required</li>
 *   <li>Dedicated server: key required (unless config disables it)</li>
 * </ul>
 * Pass key via {@code Authorization: Bearer <key>}.
 */
public final class RestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Gson GSON = new Gson();
    private final KeyManager keyManager;
    private final ConcurrentHashMap<String, long[]> rateWindows = new ConcurrentHashMap<>();

    public RestHandler(KeyManager keyManager) {
        this.keyManager = keyManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method().equals(HttpMethod.OPTIONS)) {
            send(ctx, HttpResponseStatus.OK, GSON.toJson(ok(new JsonObject())));
            return;
        }
        if (!request.decoderResult().isSuccess()) {
            send(ctx, HttpResponseStatus.BAD_REQUEST, GSON.toJson(error(1001, "Invalid request")));
            return;
        }
        QueryStringDecoder query = new QueryStringDecoder(request.uri());
        try {
            Optional<ApiKey> apiKey = resolveKey(request);
            if (keyManager != null && keyManager.isAuthRequired() && apiKey.isEmpty()) {
                send(ctx, HttpResponseStatus.UNAUTHORIZED,
                        GSON.toJson(MinecraftThread.call(() -> envelope(error(2001, "API key required. Use /webmonitor key generate")))));
                return;
            }
            if (keyManager != null && keyManager.isAuthRequired() && apiKey.isPresent()
                    && isRateLimited(apiKey.get().getKey())) {
                send(ctx, HttpResponseStatus.TOO_MANY_REQUESTS,
                        GSON.toJson(MinecraftThread.call(() -> envelope(error(4001, "rate limit exceeded")))));
                return;
            }
            JsonObject response = MinecraftThread.call(() -> envelope(route(request.method(), query.path(), query, body(request))));
            send(ctx, status(response), GSON.toJson(response));
        } catch (Exception e) {
            WebMonitorMod.LOGGER.error("[REST] Internal error for {} {}", request.method(), request.uri(), e);
            send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    errorJson(3002, "Internal error: " + e.getMessage()));
        }
    }

    private JsonObject route(HttpMethod method, String path, QueryStringDecoder q, JsonObject body) {
        if (method.equals(HttpMethod.GET) && path.equals("/api/v1/status")) {
            return cachedRead("status", () -> {
                JsonObject d = new JsonObject();
                MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                d.addProperty("running", s != null);
                d.addProperty("players", s == null ? 0 : s.getPlayerCount());
                d.addProperty("max_players", s == null ? 0 : s.getMaxPlayers());
                d.addProperty("motd", s == null ? "" : s.getMotd());
                d.addProperty("version", s == null ? "" : s.getServerVersion());
                d.addProperty("dedicated", s != null && s.isDedicatedServer());
                d.addProperty("auth_required", keyManager != null && keyManager.isAuthRequired());
                d.addProperty("web_running", WebMonitorMod.getWebServer() != null && WebMonitorMod.getWebServer().isRunning());
                d.addProperty("web_port", WebMonitorMod.getWebServer() == null ? -1 : WebMonitorMod.getWebServer().getBoundPort());
                return ok(d);
            });
        }
        if (method.equals(HttpMethod.GET) && path.equals("/api/v1/tps")) {
            return cachedRead("tps", () -> {
                JsonObject d = new JsonObject();
                MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                double avgTick = s == null ? 0.0 : s.getAverageTickTime();
                d.addProperty("tps", s == null ? 0.0 : avgTick == 0 ? 20.0 : Math.min(20.0, 1000.0 / Math.max(0.001, avgTick)));
                d.addProperty("average_tick_ms", avgTick);
                d.addProperty("tick_count", s == null ? 0 : s.getTickCount());
                return ok(d);
            });
        }
        if (method.equals(HttpMethod.GET) && path.equals("/api/v1/worlds")) {
            return cachedRead("worlds", () -> {
                JsonArray worlds = new JsonArray();
                MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                if (s != null) {
                    s.getAllLevels().forEach(level -> {
                        JsonObject w = new JsonObject();
                        w.addProperty("dimension", level.dimension().location().toString());
                        w.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
                        w.addProperty("difficulty", level.getDifficulty().getKey());
                        worlds.add(w);
                    });
                }
                JsonObject d = new JsonObject();
                d.add("worlds", worlds);
                return ok(d);
            });
        }
        if (method.equals(HttpMethod.GET) && path.startsWith("/api/v1/worlds/")) {
            String dim = path.substring("/api/v1/worlds/".length());
            if (dim.isEmpty()) return error(1002, "Not found: " + path);
            ServerLevel level = BlockApi.level(dim);
            if (level == null) return error(1002, "Dimension not found: " + dim);
            int dayTime = (int) (level.getDayTime() % 24000L);
            JsonObject d = new JsonObject();
            d.addProperty("dimension", level.dimension().location().toString());
            d.addProperty("day_time", dayTime);
            d.addProperty("game_time", level.getGameTime());
            d.addProperty("is_raining", level.isRaining());
            d.addProperty("is_thundering", level.isThundering());
            d.addProperty("rain_level", level.getRainLevel(1.0F));
            d.addProperty("thunder_level", level.getThunderLevel(1.0F));
            d.addProperty("difficulty", level.getDifficulty().getKey());
            d.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            d.addProperty("hardcore", level.getLevelData().isHardcore());
            return ok(d);
        }

        if (path.startsWith("/api/v1/world/"))
            return worldRoute(method, path, q, body);

        if (path.equals("/api/v1/events/catalog") && method.equals(HttpMethod.GET)) {
            JsonArray arr = new JsonArray();
            for (String e : EventCatalog.getEvents()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", e);
                item.addProperty("description", EventCatalog.describe(e));
                arr.add(item);
            }
            JsonObject d = new JsonObject();
            d.add("events", arr);
            return ok(d);
        }

        return error(1002, "Not found: " + path);
    }

    private JsonObject worldRoute(HttpMethod method, String path, QueryStringDecoder q, JsonObject body) {
        String[] p = path.split("/");
        if (p.length < 6) return error(1002, "Not found: " + path);
        String dim = p[4];
        int x = integer(q, "x"), y = integer(q, "y"), z = integer(q, "z");
        String rest = path.substring(("/api/v1/world/" + dim).length());

        if (rest.equals("/blockstate") && method.equals(HttpMethod.GET))
            return BlockApi.getProperty(dim, x, y, z, param(q, "key", null));
        if (rest.equals("/blockentity") && method.equals(HttpMethod.GET))
            return BlockApi.getBlockEntityNbt(dim, x, y, z, param(q, "path", null), false);
        if (rest.equals("/blockentity/snbt") && method.equals(HttpMethod.GET))
            return BlockApi.getBlockEntityNbt(dim, x, y, z, param(q, "path", null), true);
        if (rest.equals("/blockentity/capability") && method.equals(HttpMethod.GET))
            return CapabilityApi.getCapability(dim, x, y, z, param(q, "cap", null));
        if (rest.equals("/entities/aabb") && method.equals(HttpMethod.GET))
            return EntityApi.getEntitiesInAABB(dim,
                    decimal(q, "minX"), decimal(q, "minY"), decimal(q, "minZ"),
                    decimal(q, "maxX"), decimal(q, "maxY"), decimal(q, "maxZ"),
                    param(q, "type", null), Math.max(1, integer(q, "limit", 50)));
        if (rest.startsWith("/entity/") && method.equals(HttpMethod.GET)) {
            String remainder = rest.substring(8);
            int slashIdx = remainder.indexOf('/');
            if (slashIdx > 0) {
                int eid = parseInt(remainder.substring(0, slashIdx));
                String sub = remainder.substring(slashIdx);
                if (sub.equals("/capability"))
                    return EntityApi.getEntityCapability(dim, eid, param(q, "cap", null));
                return error(1002, "Not found: " + path);
            }
            int eid = parseInt(remainder);
            return EntityApi.getEntity(dim, eid);
        }
        if (rest.startsWith("/player/") && method.equals(HttpMethod.GET))
            return EntityApi.getPlayer(dim, rest.substring(8));
        if (rest.equals("/players") && method.equals(HttpMethod.GET))
            return EntityApi.getPlayers(dim);

        if (method.equals(HttpMethod.POST)) {
            if (rest.equals("/blocks"))
                return BatchApi.query(dim, body, param(q, "type", null));
            if (rest.equals("/blockstates"))
                return BlockApi.batchBlockStates(dim, body);
            if (rest.equals("/blockentities"))
                return BlockApi.batchBlockEntities(dim, body, false);
            if (rest.equals("/blockentities/snbt"))
                return BlockApi.batchBlockEntities(dim, body, true);
            if (rest.equals("/blockentity/capabilities"))
                return CapabilityApi.batchCapabilities(dim, body);
        }

        return error(1002, "Not found: " + path);
    }

    private Optional<ApiKey> resolveKey(FullHttpRequest request) {
        if (keyManager == null) return Optional.empty();
        String key = null;
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) key = header.substring(7).trim();
        return keyManager.get(key);
    }

    /** Simple per-key sliding window: max N requests per 60s. */
    private boolean isRateLimited(String key) {
        int limit = WebMonitorMod.getConfig() == null ? 120 : WebMonitorMod.getConfig().getRateLimitPerMinute();
        if (limit <= 0) return false;
        long now = System.currentTimeMillis();
        long[] window = rateWindows.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] >= 60_000L) {
                window[0] = now;
                window[1] = 0;
            }
            window[1]++;
            return window[1] > limit;
        }
    }

    private JsonObject cachedRead(String cacheKey, RouteHandler loader) {
        return ServerState.get("rest:" + cacheKey, loader::run);
    }

    @FunctionalInterface
    private interface RouteHandler { JsonObject run(); }

    private static JsonObject body(FullHttpRequest r) {
        String text = r.content().toString(CharsetUtil.UTF_8);
        if (text == null || text.isBlank()) return new JsonObject();
        try { return GSON.fromJson(text, JsonObject.class); } catch (Exception e) { return new JsonObject(); }
    }
    private static String param(QueryStringDecoder q, String k, String d) {
        List<String> values = q.parameters().get(k);
        return values == null || values.isEmpty() ? d : values.get(0);
    }
    private static int integer(QueryStringDecoder q, String k) { return integer(q, k, 0); }
    private static int integer(QueryStringDecoder q, String k, int d) {
        try { return Integer.parseInt(param(q, k, String.valueOf(d))); } catch (NumberFormatException e) { return d; }
    }
    private static double decimal(QueryStringDecoder q, String k) {
        try { return Double.parseDouble(param(q, k, "0")); } catch (NumberFormatException e) { return 0; }
    }
    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static JsonObject ok(JsonObject d) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 0);
        o.addProperty("msg", "ok");
        o.add("data", d);
        return o;
    }
    private static JsonObject error(int c, String m) {
        JsonObject o = new JsonObject();
        o.addProperty("code", c);
        o.addProperty("msg", m);
        return o;
    }
    private static String errorJson(int c, String m) { return GSON.toJson(error(c, m)); }

    private static HttpResponseStatus status(JsonObject response) {
        if (!response.has("code")) return HttpResponseStatus.OK;
        return switch (response.get("code").getAsInt()) {
            case 0 -> HttpResponseStatus.OK;
            case 1001, 3001 -> HttpResponseStatus.BAD_REQUEST;
            case 1002 -> HttpResponseStatus.NOT_FOUND;
            case 2001 -> HttpResponseStatus.UNAUTHORIZED;
            case 2002, 2003 -> HttpResponseStatus.FORBIDDEN;
            case 4001 -> HttpResponseStatus.TOO_MANY_REQUESTS;
            default -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
        };
    }
    private static JsonObject envelope(JsonObject r) {
        r.addProperty("trace_id", UUID.randomUUID().toString());
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        r.addProperty("server_tick", s == null ? 0 : s.getTickCount());
        return r;
    }
    private static void send(ChannelHandlerContext c, HttpResponseStatus s, String b) {
        FullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, s, Unpooled.copiedBuffer(b, CharsetUtil.UTF_8));
        r.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        r.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, r.content().readableBytes());
        r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS");
        r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type");
        c.writeAndFlush(r).addListener(ChannelFutureListener.CLOSE);
    }
}
