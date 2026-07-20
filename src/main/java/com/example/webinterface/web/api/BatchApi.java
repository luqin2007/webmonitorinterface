package com.example.webinterface.web.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Batch block query API (read-only). */
public final class BatchApi {
    private BatchApi() {}

    public static JsonObject query(JsonObject body, boolean countOnly) {
        if (body == null) body = new JsonObject();
        String dimension = string(body, "dimension", string(body, "dim", "minecraft:overworld"));
        JsonArray positions = body.has("positions") && body.get("positions").isJsonArray()
                ? body.getAsJsonArray("positions") : new JsonArray();

        if (body.has("region") && body.get("region").isJsonObject()) {
            JsonObject r = body.getAsJsonObject("region");
            int minX = integer(r, "minX", 0), maxX = integer(r, "maxX", 0);
            int minY = integer(r, "minY", 0), maxY = integer(r, "maxY", 0);
            int minZ = integer(r, "minZ", 0), maxZ = integer(r, "maxZ", 0);
            int regionTotal = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            int regionCap = Math.min(Math.max(regionTotal, 0), 4096);
            positions = new JsonArray();
            outer:
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        JsonObject p = new JsonObject();
                        p.addProperty("x", x);
                        p.addProperty("y", y);
                        p.addProperty("z", z);
                        positions.add(p);
                        if (positions.size() >= regionCap) break outer;
                    }
                }
            }
        }
        if (positions.size() == 0) return error(1001, "positions or region is required");

        String filter = string(body, "filter", null);
        int limit = integer(body, "limit", 4096);
        if (limit < 1) limit = 4096;

        JsonArray result = new JsonArray();
        int matched = 0;
        int scanned = 0;
        for (JsonElement element : positions) {
            if (scanned >= limit) break;
            if (!element.isJsonObject()) continue;
            JsonObject p = element.getAsJsonObject();
            int x = integer(p, "x", 0);
            int y = integer(p, "y", 0);
            int z = integer(p, "z", 0);
            scanned++;
            JsonObject value = BlockApi.getBlock(dimension, x, y, z);
            if (value.get("code").getAsInt() != 0) continue;
            if (filter != null && !filter.isBlank()
                    && value.has("data") && value.getAsJsonObject("data").has("state")
                    && !filter.equals(value.getAsJsonObject("data").getAsJsonObject("state").get("id").getAsString())) {
                continue;
            }
            matched++;
            if (!countOnly && result.size() < 1000) {
                result.add(value.get("data"));
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("scanned", scanned);
        data.addProperty("count", matched);
        data.addProperty("limit", limit);
        if (!countOnly) data.add("blocks", result);
        return ok(data);
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }

    public static JsonObject ok(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 0);
        o.addProperty("msg", "ok");
        o.add("data", data);
        return o;
    }

    public static JsonObject error(int code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("msg", msg);
        return o;
    }
}
