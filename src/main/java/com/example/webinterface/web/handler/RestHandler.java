package com.example.webinterface.web.handler;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.model.ServerState;
import com.example.webinterface.web.api.AuthApi;
import com.example.webinterface.web.api.BatchApi;
import com.example.webinterface.web.api.BlockApi;
import com.example.webinterface.web.api.CapabilityApi;
import com.example.webinterface.web.api.EntityApi;
import com.example.webinterface.web.auth.AuthManager;
import com.example.webinterface.web.auth.Session;
import com.example.webinterface.web.event.events.EventCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Exact REST routing for the public v1 API.
 *
 * <p>Routes marked "AUTH" require a valid Bearer token. Public routes (status/tps/worlds
 * and main block/entity read APIs) honour {@link AuthManager#isAllowAnonymousRead()}.
 * Write/invoke operations reject anonymous access.
 *
 * <p>Headers consumed:
 * <ul>
 *   <li>{@code Authorization: Bearer <token>}</li>
 *   <li>{@code X-Auth-Token: <token>} (alternative)</li>
 *   <li>{@code ?token=<token>} (alternative)</li>
 * </ul>
 */
public final class RestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Gson GSON = new Gson();

    private final AuthManager authManager;

    public RestHandler(AuthManager authManager) {
        this.authManager = authManager;
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
            AuthContext auth = resolveAuth(request, query);
            JsonObject response = route(request.method(), query.path(), query, body(request), auth);
            applyRateLimit(auth, response);
            send(ctx, status(response), GSON.toJson(envelope(response)));
        } catch (RateLimitedException e) {
            send(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, GSON.toJson(envelope(error(4001, "rate limit exceeded"))));
        } catch (Exception e) {
            WebInterfaceMod.LOGGER.error("[REST] Internal error for {} {}", request.method(), request.uri(), e);
            send(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, errorJson(3002, "Internal error: " + e.getMessage()));
        }
    }

    private JsonObject route(HttpMethod method, String path, QueryStringDecoder q, JsonObject body, AuthContext auth) {
        // ====== Base endpoints (public) ======
        if (method.equals(HttpMethod.GET) && path.equals("/api/v1/status")) {
            return cachedRead("status", () -> {
                JsonObject d = new JsonObject();
                MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
                d.addProperty("running", s != null);
                d.addProperty("players", s == null ? 0 : s.getPlayerCount());
                d.addProperty("max_players", s == null ? 0 : s.getMaxPlayers());
                d.addProperty("motd", s == null ? "" : s.getMotd());
                d.addProperty("version", s == null ? "" : s.getServerVersion());
                d.addProperty("auth_enabled", authManager != null && authManager.isAuthEnabled());
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

        if (method.equals(HttpMethod.POST) && path.equals("/api/v1/blocks/batch"))
            return requireRead(auth, () -> BatchApi.query(body, false));
        if (method.equals(HttpMethod.POST) && path.equals("/api/v1/blocks/batch/count"))
            return requireRead(auth, () -> BatchApi.query(body, true));

        if (method.equals(HttpMethod.GET) && path.equals("/api/v1/capabilities"))
            return requireRead(auth, () -> CapabilityApi.listCapabilities(param(q, "dim", "minecraft:overworld"), integer(q, "x"), integer(q, "y"), integer(q, "z")));

        if (path.startsWith("/api/v1/world/"))
            return worldRoute(method, path, q, body, auth);
        if (path.startsWith("/api/v1/capabilities/"))
            return capabilityRoute(method, path, q, body, auth);

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

        // ====== Unified auth namespace ======
        if (path.startsWith("/api/v1/auth/")) return authRoute(method, path, q, body, auth);
        // ====== Legacy /permissions/* - now redirects to /auth/ ======
        if (path.startsWith("/api/v1/permissions/methods") && method.equals(HttpMethod.GET))
            return AuthApi.methods(authManager, auth.session);
        if (path.startsWith("/api/v1/permissions") && method.equals(HttpMethod.GET))
            return AuthApi.viewPermissions(authManager, auth.session);
        if (path.startsWith("/api/v1/permissions") && method.equals(HttpMethod.PUT))
            return AuthApi.updatePermissions(authManager, auth.session, body);

        return error(1002, "Not found: " + path);
    }

    private JsonObject authRoute(HttpMethod method, String path, QueryStringDecoder q, JsonObject body, AuthContext auth) {
        String sub = path.substring("/api/v1/auth/".length());
        if (sub.equals("login") && method.equals(HttpMethod.POST))
            return AuthApi.login(authManager, body);
        if (sub.equals("logout") && method.equals(HttpMethod.POST))
            return AuthApi.logout(authManager, auth.rawToken);
        if (sub.equals("me") && method.equals(HttpMethod.GET))
            return AuthApi.me(authManager, auth.session);
        if (sub.equals("methods") && method.equals(HttpMethod.GET))
            return AuthApi.methods(authManager, auth.session);
        if (sub.equals("permissions") && method.equals(HttpMethod.GET))
            return AuthApi.viewPermissions(authManager, auth.session);
        if (sub.equals("permissions") && method.equals(HttpMethod.PUT))
            return AuthApi.updatePermissions(authManager, auth.session, body);
        if (sub.equals("refresh") && method.equals(HttpMethod.POST))
            return AuthApi.refresh(authManager, auth.session);
        if (sub.equals("sessions") && method.equals(HttpMethod.GET)) {
            if (auth.session.isEmpty() || !auth.session.get().canManagePermissions())
                return error(2003, "op level required");
            JsonObject d = new JsonObject();
            d.addProperty("active_sessions", authManager.getActiveSessionCount());
            JsonArray users = authManager.listUsers();
            d.add("users", users);
            return ok(d);
        }
        return error(1002, "Not found: " + path);
    }

    private JsonObject capabilityRoute(HttpMethod method, String path, QueryStringDecoder q, JsonObject body, AuthContext auth) {
        String[] p = path.split("/");
        if (p.length < 6) return error(1002, "Not found: " + path);
        String target = p[4], capability = p[5];
        if (!"block".equals(target) && !"blockentity".equals(target))
            return error(1001, "Only block capability references are supported");
        String dimInit = param(q, "dim", "minecraft:overworld");
        int xInit = integer(q, "x"), yInit = integer(q, "y"), zInit = integer(q, "z");
        String ref = param(q, "ref", null);
        String dim = dimInit;
        int x = xInit, y = yInit, z = zInit;
        if (ref != null) {
            String[] parts = ref.split(",", 4);
            if (parts.length == 4) {
                dim = parts[0]; x = parseInt(parts[1]); y = parseInt(parts[2]); z = parseInt(parts[3]);
            }
        }
        final String fDim = dim;
        final int fx = x, fy = y, fz = z;
        if (path.endsWith("/execute") && method.equals(HttpMethod.POST)) {
            return requireWrite(auth, "CapabilityApi.execute", () ->
                    CapabilityApi.execute(fDim, fx, fy, fz, capability, string(body, "operation", null), body));
        }
        if (method.equals(HttpMethod.GET)) {
            return requireRead(auth, () ->
                    CapabilityApi.getCapability(fDim, fx, fy, fz, capability));
        }
        return error(1002, "Not found: " + path);
    }

    private JsonObject worldRoute(HttpMethod method, String path, QueryStringDecoder q, JsonObject body, AuthContext auth) {
        String[] p = path.split("/");
        if (p.length < 6) return error(1002, "Not found: " + path);
        String dim = p[4];
        int x = integer(q, "x"), y = integer(q, "y"), z = integer(q, "z");
        String rest = path.substring(("/api/v1/world/" + dim).length());

        if (rest.equals("/block") && method.equals(HttpMethod.GET))
            return requireRead(auth, () -> cachedRead("block|" + dim + "|" + x + "|" + y + "|" + z,
                    () -> BlockApi.getBlock(dim, x, y, z)));
        if (rest.equals("/block/property") && method.equals(HttpMethod.GET))
            return requireRead(auth, () -> BlockApi.getProperty(dim, x, y, z, param(q, "key", null)));
        if (rest.equals("/block/blockentity") && method.equals(HttpMethod.GET))
            return requireRead(auth, () -> BlockApi.getBlockEntityNbt(dim, x, y, z, param(q, "path", null)));
        if (rest.equals("/block/blockentity/invoke") && method.equals(HttpMethod.POST))
            return requireWrite(auth, "BlockEntity.invoke", () ->
                    BlockApi.invokeBlockEntity(dim, x, y, z, string(body, "method", null), array(body, "args"), auth.session));
        if (rest.equals("/block/capability") && method.equals(HttpMethod.GET))
            return requireRead(auth, () -> CapabilityApi.getCapability(dim, x, y, z, param(q, "cap", null)));
        if (rest.equals("/entities/aabb") && method.equals(HttpMethod.GET))
            return requireRead(auth, () -> EntityApi.getEntitiesInAABB(dim,
                    decimal(q, "minX"), decimal(q, "minY"), decimal(q, "minZ"),
                    decimal(q, "maxX"), decimal(q, "maxY"), decimal(q, "maxZ"),
                    param(q, "type", null), Math.max(1, integer(q, "limit", 50))));
        if (rest.startsWith("/entity/") && method.equals(HttpMethod.GET)) {
            int eid = parseInt(rest.substring(8));
            return requireRead(auth, () -> EntityApi.getEntity(dim, eid));
        }
        if (rest.startsWith("/player/") && method.equals(HttpMethod.GET))
            return requireRead(auth, () -> EntityApi.getPlayer(dim, rest.substring(8)));
        return error(1002, "Not found: " + path);
    }

    // ============ Auth helpers ============

    private AuthContext resolveAuth(FullHttpRequest request, QueryStringDecoder query) {
        if (authManager == null) return new AuthContext(Optional.empty(), null);
        String token = null;
        String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) token = header.substring(7).trim();
        if (token == null || token.isBlank()) token = request.headers().get("X-Auth-Token");
        if (token == null || token.isBlank()) token = param(query, "token", null);
        Optional<Session> session = authManager.getSession(token);
        return new AuthContext(session, token);
    }

    private void applyRateLimit(AuthContext auth, JsonObject response) {
        if (auth == null || auth.session.isEmpty()) return;
        // Anonymous doesn't get rate-limited here (endpoints reject anonymous writes anyway,
        // anonymous reads don't bind to a per-session bucket). Use IP-based limit separately if needed.
        if (!auth.session.get().tryRateLimit()) {
            response.addProperty("code", 4001);
            response.addProperty("msg", "rate limit exceeded");
        }
    }

    /** Allow read if logged in OR anonymous read enabled. */
    private JsonObject requireRead(AuthContext auth, RouteHandler route) {
        if (auth == null) return route.run();
        if (auth.session.isEmpty() && authManager != null && !authManager.isAllowAnonymousRead()) {
            return error(2001, "Authentication required");
        }
        return route.run();
    }

    /** Write/invoke requires an actual session. The {@code methodKey} is checked against MethodGuard. */
    private JsonObject requireWrite(AuthContext auth, String methodKey, RouteHandler route) {
        if (auth == null || auth.session.isEmpty())
            return error(2001, "Authentication required for write/invoke operations");
        if (authManager != null) {
            var check = authManager.getMethodGuard().check(auth.session.get().getLevel(), methodKey);
            if (!check.allowed) return error(2002, "Method invocation denied: " + check.reason);
        }
        return route.run();
    }

    /** Cached read wrapper - uses ServerState 1-tick cache. */
    private JsonObject cachedRead(String cacheKey, RouteHandler loader) {
        return ServerState.get("rest:" + cacheKey, loader::run);
    }

    @FunctionalInterface
    private interface RouteHandler { JsonObject run(); }

    private record AuthContext(Optional<Session> session, String rawToken) {}

    // ============ Parsing helpers ============
    private static final JsonObject body(FullHttpRequest r) {
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
    private static int integer(JsonObject o, String k, int d) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : d;
    }
    private static String string(JsonObject o, String k, String d) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }
    private static JsonArray array(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    // ============ Envelope helpers ============
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
        r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS");
        r.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, X-Auth-Token, Content-Type");
        c.writeAndFlush(r).addListener(ChannelFutureListener.CLOSE);
    }

    private static final class RateLimitedException extends RuntimeException {
        RateLimitedException() { super(null, null, false, false); }
    }
}
