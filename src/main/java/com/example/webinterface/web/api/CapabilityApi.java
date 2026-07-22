package com.example.webinterface.web.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Read-only Forge capability snapshots.
 * Works with any ICapabilityProvider (BlockEntity, Entity, etc.).
 * No mutations (receive/extract/insert/drain) are exposed — too dangerous for a monitor API.
 */
public final class CapabilityApi {
    private CapabilityApi() {}

    public static JsonObject listCapabilities(ICapabilityProvider provider) {
        JsonArray capabilities = new JsonArray();
        addAvailability(capabilities, "energy", provider.getCapability(ForgeCapabilities.ENERGY));
        addAvailability(capabilities, "items", provider.getCapability(ForgeCapabilities.ITEM_HANDLER));
        addAvailability(capabilities, "fluid", provider.getCapability(ForgeCapabilities.FLUID_HANDLER));
        JsonObject data = new JsonObject();
        data.add("capabilities", capabilities);
        return ok(data);
    }

    public static JsonObject getCapability(String dimension, int x, int y, int z, String name) {
        BlockEntity be = blockEntity(dimension, x, y, z);
        if (be == null) return error(1002, "Block entity not found or chunk is not loaded");
        return getCapability(be, name);
    }

    public static JsonObject getCapability(ICapabilityProvider provider, String name) {
        JsonObject snapshot = snapshot(provider, name, null);
        if (snapshot == null) return error(1002, "Capability not available: " + name);
        JsonObject data = new JsonObject();
        data.addProperty("capability", normalize(name));
        data.add("snapshot", snapshot);
        return ok(data);
    }

    public static JsonObject getEntityCapability(String dimension, int entityId, String name) {
        ServerLevel level = level(dimension);
        if (level == null) return error(1002, "Dimension not found: " + dimension);
        Entity entity = level.getEntity(entityId);
        if (entity == null) return error(1002, "Entity not found: " + entityId);
        return getCapability(entity, name);
    }

    public static JsonObject batchCapabilities(String dimension, JsonObject body) {
        ServerLevel level = level(dimension);
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
            JsonObject pos = new JsonObject();
            pos.addProperty("x", x);
            pos.addProperty("y", y);
            pos.addProperty("z", z);
            entry.add("pos", pos);
            BlockPos bp = new BlockPos(x, y, z);
            if (level.hasChunkAt(bp)) {
                BlockEntity be = level.getBlockEntity(bp);
                if (be != null) {
                    entry.add("capabilities", listCapabilities(be).get("data"));
                }
            }
            results.add(entry);
        }
        JsonObject data = new JsonObject();
        data.add("results", results);
        return ok(data);
    }

    private static void addAvailability(JsonArray result, String name, LazyOptional<?> capability) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("available", capability.isPresent());
        result.add(entry);
    }

    private static JsonObject snapshot(ICapabilityProvider provider, String name, Direction side) {
        return switch (normalize(name)) {
            case "energy" -> {
                IEnergyStorage value = provider.getCapability(ForgeCapabilities.ENERGY, side).orElse(null);
                yield value == null ? null : energySnapshot(value);
            }
            case "items" -> {
                IItemHandler value = provider.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                yield value == null ? null : itemSnapshot(value);
            }
            case "fluid" -> {
                IFluidHandler value = provider.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
                yield value == null ? null : fluidSnapshot(value);
            }
            default -> null;
        };
    }

    private static JsonObject energySnapshot(IEnergyStorage storage) {
        JsonObject o = new JsonObject();
        o.addProperty("stored", storage.getEnergyStored());
        o.addProperty("capacity", storage.getMaxEnergyStored());
        o.addProperty("can_receive", storage.canReceive());
        o.addProperty("can_extract", storage.canExtract());
        return o;
    }

    private static JsonObject itemSnapshot(IItemHandler handler) {
        JsonObject o = new JsonObject();
        JsonArray slots = new JsonArray();
        for (int i = 0; i < handler.getSlots(); i++) {
            JsonObject slot = stackJson(handler.getStackInSlot(i));
            slot.addProperty("slot", i);
            slot.addProperty("limit", handler.getSlotLimit(i));
            slots.add(slot);
        }
        o.addProperty("slots", handler.getSlots());
        o.add("items", slots);
        return o;
    }

    private static JsonObject fluidSnapshot(IFluidHandler handler) {
        JsonObject o = new JsonObject();
        JsonArray tanks = new JsonArray();
        for (int i = 0; i < handler.getTanks(); i++) {
            JsonObject tank = fluidJson(handler.getFluidInTank(i));
            tank.addProperty("tank", i);
            tank.addProperty("capacity", handler.getTankCapacity(i));
            tanks.add(tank);
        }
        o.add("tanks", tanks);
        return o;
    }

    private static JsonObject stackJson(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())));
        o.addProperty("count", stack.getCount());
        if (stack.hasTag()) o.add("nbt", NbtJson.toJson(stack.getTag()));
        return o;
    }

    private static JsonObject fluidJson(FluidStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(ForgeRegistries.FLUIDS.getKey(stack.getFluid())));
        o.addProperty("amount", stack.getAmount());
        if (stack.hasTag()) o.add("nbt", NbtJson.toJson(stack.getTag()));
        return o;
    }

    private static BlockEntity blockEntity(String dim, int x, int y, int z) {
        ServerLevel level = level(dim);
        BlockPos pos = new BlockPos(x, y, z);
        return level != null && level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
    }

    static ServerLevel level(String dim) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return switch (dim) {
            case "overworld", "minecraft:overworld" -> server.overworld();
            case "nether", "minecraft:the_nether" -> server.getLevel(Level.NETHER);
            case "end", "minecraft:the_end" -> server.getLevel(Level.END);
            default -> {
                ResourceLocation key = ResourceLocation.tryParse(dim);
                yield key == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, key));
            }
        };
    }

    private static int integer(JsonObject o, String key, int fallback) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }

    private static String normalize(String name) {
        if (name == null) return "";
        return switch (name) {
            case "forge:energy", "energy" -> "energy";
            case "forge:items", "items", "item" -> "items";
            case "forge:fluid", "fluid", "fluids" -> "fluid";
            default -> name;
        };
    }

    private static JsonObject ok(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("code", 0);
        o.addProperty("msg", "ok");
        o.add("data", data);
        return o;
    }

    private static JsonObject error(int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("msg", message);
        return o;
    }
}
