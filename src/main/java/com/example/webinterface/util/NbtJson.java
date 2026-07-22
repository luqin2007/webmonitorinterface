package com.example.webinterface.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Converts NBT values to JSON without losing their nested structure. */
public final class NbtJson {
    private NbtJson() {}

    public static JsonElement toJson(Tag tag) {
        if (tag == null) return JsonNull.INSTANCE;
        return switch (tag.getId()) {
            case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG, Tag.TAG_FLOAT, Tag.TAG_DOUBLE ->
                    new JsonPrimitive(((NumericTag) tag).getAsNumber());
            case Tag.TAG_STRING -> new JsonPrimitive(((StringTag) tag).getAsString());
            case Tag.TAG_BYTE_ARRAY -> bytes(((ByteArrayTag) tag).getAsByteArray());
            case Tag.TAG_INT_ARRAY -> integers(((IntArrayTag) tag).getAsIntArray());
            case Tag.TAG_LONG_ARRAY -> longs(((LongArrayTag) tag).getAsLongArray());
            case Tag.TAG_LIST -> collection((CollectionTag<?>) tag);
            case Tag.TAG_COMPOUND -> compound((CompoundTag) tag);
            default -> JsonNull.INSTANCE;
        };
    }

    private static JsonObject compound(CompoundTag tag) {
        JsonObject result = new JsonObject();
        for (String key : tag.getAllKeys()) result.add(key, toJson(tag.get(key)));
        return result;
    }

    private static JsonArray collection(CollectionTag<?> tag) {
        JsonArray result = new JsonArray();
        for (Tag value : tag) result.add(toJson(value));
        return result;
    }

    private static JsonArray bytes(byte[] values) {
        JsonArray result = new JsonArray();
        for (byte value : values) result.add(value);
        return result;
    }

    private static JsonArray integers(int[] values) {
        JsonArray result = new JsonArray();
        for (int value : values) result.add(value);
        return result;
    }

    private static JsonArray longs(long[] values) {
        JsonArray result = new JsonArray();
        for (long value : values) result.add(value);
        return result;
    }
}
