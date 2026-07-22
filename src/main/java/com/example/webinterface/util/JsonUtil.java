package com.example.webinterface.util;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

public class JsonUtil {

    public static JsonObject ok(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 0);
        o.addProperty("msg", "ok");
        o.add("data", data);
        return o;
    }

    public static JsonObject error(int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("msg", message);
        return o;
    }

    public static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    public static int integer(JsonObject o, String key, int fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }

    public static JsonObject pos(BlockPos blockPos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", blockPos.getX());
        o.addProperty("y", blockPos.getY());
        o.addProperty("z", blockPos.getZ());
        return o;
    }

    public static JsonObject pos(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    public static BlockPos pos(JsonObject object) {
        int x = integer(object, "x", 0);
        int y = integer(object, "y", 0);
        int z = integer(object, "z", 0);
        return new BlockPos(x, y, z);
    }
}
