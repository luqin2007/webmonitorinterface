package com.example.webinterface.web.api;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.security.MethodGuard;
import com.example.webinterface.web.auth.AuthManager;
import com.example.webinterface.web.auth.Session;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.core.registries.Registries;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Block state, block entity and capability-facing block operations. */
public final class BlockApi {
    private BlockApi() {}
    public static JsonObject getBlock(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension); if (level == null) return error(1002, "Dimension not found: " + dimension);
        BlockPos pos = new BlockPos(x, y, z); JsonObject data = new JsonObject(); data.addProperty("chunk_loaded", level.hasChunkAt(pos)); data.add("pos", pos(x,y,z)); data.addProperty("dimension", dimension);
        if (level.hasChunkAt(pos)) { data.add("state", stateJson(level.getBlockState(pos))); data.add("block_entity", blockEntityJson(level.getBlockEntity(pos))); }
        return ok(data);
    }
    public static JsonObject getProperty(String dimension, int x, int y, int z, String key) {
        ServerLevel level = level(dimension); BlockPos pos = new BlockPos(x,y,z); if (level == null || !level.hasChunkAt(pos)) return error(1002, "Chunk is not loaded");
        BlockState state = level.getBlockState(pos); for (Property<?> property : state.getProperties()) if (property.getName().equals(key)) { JsonObject data = new JsonObject(); data.addProperty("key", key); data.addProperty("value", state.getValue(property).toString()); return ok(data); }
        return error(1002, "Block state property not found: " + key);
    }
    public static JsonObject getBlockEntityNbt(String dimension, int x, int y, int z, String path) {
        ServerLevel level = level(dimension); BlockPos pos = new BlockPos(x,y,z); BlockEntity be = level != null && level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null; if (be == null) return error(1002, "Block entity not found");
        net.minecraft.nbt.CompoundTag tag = be.saveWithFullMetadata(); if (path != null && !path.isBlank()) { net.minecraft.nbt.Tag selected = tag; for (String part : path.split("\\.")) { if (!(selected instanceof net.minecraft.nbt.CompoundTag compound) || !compound.contains(part)) return error(1002, "NBT path not found: " + path); selected = compound.get(part); } JsonObject data = new JsonObject(); data.addProperty("path", path); data.addProperty("value", selected.toString()); return ok(data); }
        JsonObject data = new JsonObject(); data.addProperty("nbt", tag.toString()); return ok(data);
    }
    public static JsonObject invokeBlockEntity(String dimension, int x, int y, int z, String methodName, JsonArray arguments) {
        return invokeBlockEntity(dimension, x, y, z, methodName, arguments, Optional.empty());
    }
    public static JsonObject invokeBlockEntity(String dimension, int x, int y, int z, String methodName, JsonArray arguments, Optional<Session> session) {
        ServerLevel level = level(dimension); BlockPos pos = new BlockPos(x,y,z); BlockEntity be = level != null && level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null; if (be == null) return error(1002, "Block entity not found");
        if (methodName == null || !methodName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return error(1001, "Invalid method name");
        MethodGuard guard = guard();
        int level_ = session.map(Session::getLevel).orElseGet(guard::getDefaultLevel);
        MethodGuard.CheckResult permission = guard.check(level_, be.getClass().getSimpleName() + "." + methodName);
        if (!permission.allowed) return error(2002, "Method invocation denied: " + permission.reason);
        try { for (Method method : be.getClass().getMethods()) if (method.getName().equals(methodName) && Modifier.isPublic(method.getModifiers()) && method.getParameterCount() == arguments.size()) { Object[] values = convert(arguments, method.getParameterTypes()); if (values == null) continue; Object value = method.invoke(be, values); JsonObject data = new JsonObject(); data.addProperty("method", methodName); data.addProperty("result", String.valueOf(value)); return ok(data); } return error(3001, "No compatible public method: " + methodName); } catch (ReflectiveOperationException e) { return error(3002, "Block entity method failed: " + e.getCause()); }
    }
    private static MethodGuard guard() {
        AuthManager auth = WebInterfaceMod.getAuthManager();
        if (auth != null) return auth.getMethodGuard();
        throw new IllegalStateException("Auth subsystem not initialized");
    }
    private static Object[] convert(JsonArray args, Class<?>[] types) { Object[] result = new Object[types.length]; for (int i=0;i<types.length;i++) { JsonElement value=args.get(i); Class<?> type=types[i]; if (type == String.class) result[i]=value.getAsString(); else if (type == int.class || type == Integer.class) result[i]=value.getAsInt(); else if (type == long.class || type == Long.class) result[i]=value.getAsLong(); else if (type == double.class || type == Double.class) result[i]=value.getAsDouble(); else if (type == boolean.class || type == Boolean.class) result[i]=value.getAsBoolean(); else return null; } return result; }
    private static JsonObject stateJson(BlockState state) { JsonObject o=new JsonObject(); o.addProperty("id", String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock()))); JsonObject properties=new JsonObject(); for(Property<?> p:state.getProperties()) properties.addProperty(p.getName(),state.getValue(p).toString()); o.add("properties",properties); return o; }
    private static JsonObject blockEntityJson(BlockEntity be) { JsonObject o=new JsonObject(); o.addProperty("exists",be!=null); if(be!=null) { o.addProperty("type",String.valueOf(ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType()))); o.addProperty("nbt",be.saveWithFullMetadata().toString()); } return o; }
    private static JsonObject pos(int x,int y,int z) { JsonObject o=new JsonObject();o.addProperty("x",x);o.addProperty("y",y);o.addProperty("z",z);return o; }
    static ServerLevel level(String dim) { MinecraftServer server=ServerLifecycleHooks.getCurrentServer(); if(server==null)return null; return switch(dim) { case "overworld","minecraft:overworld"->server.overworld(); case "nether","minecraft:the_nether"->server.getLevel(Level.NETHER); case "end","minecraft:the_end"->server.getLevel(Level.END); default->{ResourceLocation key=ResourceLocation.tryParse(dim);yield key==null?null:server.getLevel(ResourceKey.create(Registries.DIMENSION, key));} }; }
    private static JsonObject ok(JsonObject data){JsonObject o=new JsonObject();o.addProperty("code",0);o.addProperty("msg","ok");o.add("data",data);return o;} private static JsonObject error(int code,String msg){JsonObject o=new JsonObject();o.addProperty("code",code);o.addProperty("msg",msg);return o;}
}
