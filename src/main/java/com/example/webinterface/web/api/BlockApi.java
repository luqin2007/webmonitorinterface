package com.example.webinterface.web.api;

import com.example.webinterface.web.util.WorldUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

/** Block state and block-entity NBT (read-only; no method invocation). */
public final class BlockApi {
    private BlockApi() {}

    public static JsonObject getBlock(String dimension, int x, int y, int z) {
        ServerLevel level = WorldUtil.level(dimension);
        if (level == null) return error(1002, "Dimension not found: " + dimension);
        BlockPos pos = new BlockPos(x, y, z);
        JsonObject data = new JsonObject();
        data.addProperty("chunk_loaded", level.hasChunkAt(pos));
        data.add("pos", pos(x, y, z));
        data.addProperty("dimension", dimension);
        if (level.hasChunkAt(pos)) {
            data.add("state", stateJson(level.getBlockState(pos)));
            data.add("block_entity", blockEntityJson(level.getBlockEntity(pos)));
        }
        return ok(data);
    }

    public static JsonObject getProperty(String dimension, int x, int y, int z, String key) {
        ServerLevel level = WorldUtil.level(dimension);
        BlockPos pos = new BlockPos(x, y, z);
        if (level == null || !level.hasChunkAt(pos)) return error(1002, "Chunk is not loaded");
        BlockState state = level.getBlockState(pos);
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(key)) {
                JsonObject data = new JsonObject();
                data.addProperty("key", key);
                data.addProperty("value", state.getValue(property).toString());
                return ok(data);
            }
        }
        return error(1002, "Block state property not found: " + key);
    }

    public static JsonObject getBlockEntityNbt(String dimension, int x, int y, int z, String path, boolean snbt) {
        ServerLevel level = WorldUtil.level(dimension);
        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity be = level != null && level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
        if (be == null) return error(1002, "Block entity not found");
        CompoundTag tag = be.saveWithFullMetadata();
        if (path != null && !path.isBlank()) {
            Tag selected = tag;
            for (String part : path.split("\\.")) {
                if (!(selected instanceof CompoundTag compound) || !compound.contains(part)) {
                    return error(1002, "NBT path not found: " + path);
                }
                selected = compound.get(part);
            }
            JsonObject data = new JsonObject();
            data.addProperty("path", path);
            if (snbt) data.addProperty("value", selected == null ? "null" : selected.toString());
            else data.add("value", NbtJson.toJson(selected));
            return ok(data);
        }
        JsonObject data = new JsonObject();
        if (snbt) data.addProperty("nbt", tag.toString());
        else data.add("nbt", NbtJson.toJson(tag));
        return ok(data);
    }

    public static JsonObject batchBlockStates(String dimension, JsonObject body) {
        ServerLevel level = WorldUtil.level(dimension);
        if (level == null) return error(1002, "Dimension not found: " + dimension);
        JsonArray positions = body.has("positions") && body.get("positions").isJsonArray()
                ? body.getAsJsonArray("positions") : new JsonArray();
        if (positions.size() == 0) return error(1001, "positions array is required");
        JsonArray results = new JsonArray();
        for (JsonElement element : positions) {
            if (!element.isJsonObject()) continue;
            JsonObject p = element.getAsJsonObject();
            int x = integer(p, "x", 0), y = integer(p, "y", 0), z = integer(p, "z", 0);
            JsonObject entry = new JsonObject();
            entry.add("pos", pos(x, y, z));
            BlockPos bp = new BlockPos(x, y, z);
            if (level.hasChunkAt(bp)) {
                entry.add("state", stateJson(level.getBlockState(bp)));
            }
            results.add(entry);
        }
        JsonObject data = new JsonObject();
        data.add("results", results);
        return ok(data);
    }

    public static JsonObject batchBlockEntities(String dimension, JsonObject body, boolean snbt) {
        ServerLevel level = WorldUtil.level(dimension);
        if (level == null) return error(1002, "Dimension not found: " + dimension);
        JsonArray positions = body.has("positions") && body.get("positions").isJsonArray()
                ? body.getAsJsonArray("positions") : new JsonArray();
        if (positions.size() == 0) return error(1001, "positions array is required");
        JsonArray results = new JsonArray();
        for (JsonElement element : positions) {
            if (!element.isJsonObject()) continue;
            JsonObject p = element.getAsJsonObject();
            int x = integer(p, "x", 0), y = integer(p, "y", 0), z = integer(p, "z", 0);
            JsonObject entry = new JsonObject();
            entry.add("pos", pos(x, y, z));
            BlockPos bp = new BlockPos(x, y, z);
            if (level.hasChunkAt(bp)) {
                BlockEntity be = level.getBlockEntity(bp);
                if (be != null) {
                    CompoundTag tag = be.saveWithFullMetadata();
                    if (snbt) entry.addProperty("nbt", tag.toString());
                    else entry.add("nbt", NbtJson.toJson(tag));
                }
            }
            results.add(entry);
        }
        JsonObject data = new JsonObject();
        data.add("results", results);
        return ok(data);
    }

    private static JsonObject stateJson(BlockState state) {
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())));
        JsonObject properties = new JsonObject();
        for (Property<?> p : state.getProperties()) {
            properties.addProperty(p.getName(), state.getValue(p).toString());
        }
        o.add("properties", properties);
        return o;
    }

    private static JsonObject blockEntityJson(BlockEntity be) {
        JsonObject o = new JsonObject();
        o.addProperty("exists", be != null);
        if (be != null) {
            o.addProperty("type", String.valueOf(ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType())));
            o.add("nbt", NbtJson.toJson(be.saveWithFullMetadata()));
        }
        return o;
    }

    private static JsonObject pos(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }

    private static JsonObject ok(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 0);
        o.addProperty("msg", "ok");
        o.add("data", data);
        return o;
    }

    private static JsonObject error(int code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("msg", msg);
        return o;
    }
}
