package com.example.webinterface.security;

import com.example.webinterface.WebMonitorMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player API-key store. Keys are generated in-game via {@code /webmonitor key generate}
 * and persisted under {@code config/web_monitor_interface-keys.json}.
 */
public final class KeyManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Map<String, ApiKey> keys = new ConcurrentHashMap<>();
    private final Path storagePath;

    public KeyManager() {
        this.storagePath = FMLPaths.CONFIGDIR.get().resolve("web_monitor_interface-keys.json");
        load();
    }

    public ApiKey generate(UUID ownerUuid, String ownerName, String comment) {
        String key = randomKey(32);
        while (keys.containsKey(key)) key = randomKey(32);
        ApiKey entry = new ApiKey(key, ownerUuid, ownerName, comment == null ? "" : comment, System.currentTimeMillis());
        keys.put(key, entry);
        save();
        WebMonitorMod.LOGGER.info("[Key] Generated key for {} ({}): {}...", ownerName, ownerUuid, key.substring(0, 8));
        return entry;
    }

    public boolean delete(String key) {
        if (key == null) return false;
        ApiKey removed = keys.remove(key);
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    public boolean deleteOwned(String key, UUID ownerUuid) {
        ApiKey entry = keys.get(key);
        if (entry == null) return false;
        if (!entry.getOwnerUuid().equals(ownerUuid)) return false;
        return delete(key);
    }

    public Optional<ApiKey> get(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Optional.ofNullable(keys.get(key));
    }

    public List<ApiKey> listByOwner(UUID ownerUuid) {
        List<ApiKey> result = new ArrayList<>();
        for (ApiKey k : keys.values()) {
            if (k.getOwnerUuid().equals(ownerUuid)) result.add(k);
        }
        result.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return result;
    }

    public int size() { return keys.size(); }

    /**
     * Only a physical client process (integrated server / singleplayer) may use
     * the monitor without a key. A physical dedicated-server process always
     * requires a valid key.
     */
    public boolean isAuthRequired() {
        return FMLEnvironment.dist != Dist.CLIENT;
    }

    public boolean isValid(String key) {
        return key != null && keys.containsKey(key);
    }

    private static String randomKey(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int v = buf[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private void load() {
        if (!Files.isRegularFile(storagePath)) return;
        try {
            String text = Files.readString(storagePath, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(text, JsonObject.class);
            if (root == null || !root.has("keys") || !root.get("keys").isJsonArray()) return;
            JsonArray arr = root.getAsJsonArray("keys");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String key = o.has("key") ? o.get("key").getAsString() : null;
                String uuidStr = o.has("owner_uuid") ? o.get("owner_uuid").getAsString() : null;
                if (key == null || uuidStr == null) continue;
                UUID uuid;
                try { uuid = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }
                String name = o.has("owner_name") ? o.get("owner_name").getAsString() : "";
                String comment = o.has("comment") ? o.get("comment").getAsString() : "";
                long created = o.has("created_at") ? o.get("created_at").getAsLong() : System.currentTimeMillis();
                keys.put(key, new ApiKey(key, uuid, name, comment, created));
            }
            WebMonitorMod.LOGGER.info("[Key] Loaded {} API keys from {}", keys.size(), storagePath);
        } catch (Exception e) {
            WebMonitorMod.LOGGER.error("[Key] Failed to load keys from {}", storagePath, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(storagePath.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ApiKey k : keys.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", k.getKey());
                o.addProperty("owner_uuid", k.getOwnerUuid().toString());
                o.addProperty("owner_name", k.getOwnerName());
                o.addProperty("comment", k.getComment());
                o.addProperty("created_at", k.getCreatedAt());
                arr.add(o);
            }
            root.add("keys", arr);
            Files.writeString(storagePath, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            WebMonitorMod.LOGGER.error("[Key] Failed to save keys to {}", storagePath, e);
        }
    }
}
