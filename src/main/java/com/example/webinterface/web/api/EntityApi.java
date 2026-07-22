package com.example.webinterface.web.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.UUID;

/** Entity details, player inventories, and bounded AABB queries (read-only). */
public final class EntityApi {
    private EntityApi() {}

    public static JsonObject getEntity(String dimension, int entityId) {
        ServerLevel level = BlockApi.level(dimension);
        if (level == null) return error(1002, "Dimension not found");
        Entity entity = level.getEntity(entityId);
        return entity == null ? error(1002, "Entity not found: " + entityId) : ok(entityJson(entity));
    }

    public static JsonObject getPlayer(String dimension, String id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return error(1002, "Server not available");
        ServerPlayer player = null;
        try {
            player = server.getPlayerList().getPlayer(UUID.fromString(id));
        } catch (IllegalArgumentException ignored) {
            for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
                if (candidate.getGameProfile().getName().equalsIgnoreCase(id)) {
                    player = candidate;
                    break;
                }
            }
        }
        if (player == null) return error(1002, "Player not online: " + id);
        if (dimension != null && !dimension.isBlank() && player.serverLevel() != BlockApi.level(dimension)) {
            return error(1002, "Player is not in requested dimension");
        }
        JsonObject data = entityJson(player);
        JsonObject inventory = new JsonObject();
        inventory.add("main", inventoryJson(player.getInventory().items));
        inventory.add("armor", inventoryJson(player.getInventory().armor));
        inventory.add("offhand", inventoryJson(player.getInventory().offhand));
        data.add("inventory", inventory);
        data.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
        data.addProperty("food", player.getFoodData().getFoodLevel());
        data.addProperty("saturation", player.getFoodData().getSaturationLevel());
        return ok(data);
    }

    public static JsonObject getEntitiesInAABB(String dimension, double minX, double minY, double minZ,
                                               double maxX, double maxY, double maxZ, String type, int limit) {
        ServerLevel level = BlockApi.level(dimension);
        if (level == null) return error(1002, "Dimension not found");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return error(1001, "Minimum AABB coordinates must not exceed maximum coordinates");
        }
        List<Entity> all = level.getEntities(null, new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        JsonArray entities = new JsonArray();
        int capped = Math.max(1, Math.min(limit, 1000));
        int matched = 0;
        for (Entity entity : all) {
            String eid = String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()));
            if (type != null && !type.isBlank() && !type.equals(eid)) continue;
            matched++;
            if (entities.size() < capped) entities.add(entityJson(entity));
        }
        JsonObject data = new JsonObject();
        data.addProperty("total", matched);
        data.addProperty("returned", entities.size());
        data.add("entities", entities);
        return ok(data);
    }

    public static JsonObject getPlayers(String dimension) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return error(1002, "Server not available");
        ServerLevel level = BlockApi.level(dimension);
        if (level == null) return error(1002, "Dimension not found: " + dimension);
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel() == level) {
                JsonObject entry = new JsonObject();
                entry.addProperty("displayName", player.getDisplayName().getString());
                entry.addProperty("uuid", player.getUUID().toString());
                players.add(entry);
            }
        }
        JsonObject data = new JsonObject();
        data.add("players", players);
        return ok(data);
    }

    public static JsonObject getEntityCapability(String dimension, int entityId, String cap) {
        ServerLevel level = BlockApi.level(dimension);
        if (level == null) return error(1002, "Dimension not found");
        return CapabilityApi.getEntityCapability(dimension, entityId, cap);
    }

    private static JsonObject entityJson(Entity entity) {
        JsonObject o = new JsonObject();
        o.addProperty("entity_id", entity.getId());
        o.addProperty("uuid", entity.getUUID().toString());
        o.addProperty("type", String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())));
        o.addProperty("custom_name", entity.hasCustomName() ? entity.getCustomName().getString() : "");
        o.addProperty("alive", entity.isAlive());
        o.addProperty("on_ground", entity.onGround());
        JsonObject pos = new JsonObject();
        pos.addProperty("x", entity.getX());
        pos.addProperty("y", entity.getY());
        pos.addProperty("z", entity.getZ());
        o.add("pos", pos);
        JsonObject rotation = new JsonObject();
        rotation.addProperty("yaw", entity.getYRot());
        rotation.addProperty("pitch", entity.getXRot());
        o.add("rotation", rotation);
        if (entity instanceof LivingEntity living) {
            o.addProperty("health", living.getHealth());
            o.addProperty("max_health", living.getMaxHealth());
            o.addProperty("hurt_time", living.hurtTime);
        }
        return o;
    }

    private static JsonArray inventoryJson(List<ItemStack> stacks) {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("id", String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())));
            item.addProperty("count", stack.getCount());
            if (stack.hasTag()) item.add("nbt", NbtJson.toJson(stack.getTag()));
            array.add(item);
        }
        return array;
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
