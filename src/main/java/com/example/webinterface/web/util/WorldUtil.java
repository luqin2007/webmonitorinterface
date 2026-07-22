package com.example.webinterface.web.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

public final class WorldUtil {
    private WorldUtil() {}

    public static ServerLevel level(String dim) {
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
}