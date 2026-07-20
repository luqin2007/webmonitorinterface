package com.example.webinterface.model;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe server state snapshot cache.
 *
 * <p>High-frequency read APIs (block state, entity, capability snapshots) cache
 * their JSON result for one tick to amortize reflective/MC API calls coming from
 * short-poll Web clients. The cached value is invalidated on tick advance.
 *
 * <p>Per-key cache: the caller computes its own cache-key string (e.g.
 * {@code "block|minecraft:overworld|12|64|34"}) and calls {@link #get(Map, String, Loader)}.
 */
public final class ServerState {

    private static final class Entry {
        final Object value;
        final int tick;
        Entry(Object value, int tick) {
            this.value = value;
            this.tick = tick;
        }
    }

    private static final class PerTickMap {
        final Map<String, Entry> map = new ConcurrentHashMap<>();
    }

    private static volatile PerTickMap CURRENT = new PerTickMap();
    private static volatile PerTickMap PREVIOUS = new PerTickMap();
    private static volatile int currentTick = -1;

    private ServerState() {}

    private static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    private static int currentTick() {
        MinecraftServer s = server();
        return s == null ? -1 : s.getTickCount();
    }

    /** Return cached value if present in the same tick, otherwise invoke loader. */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Loader<T> loader) {
        int tick = currentTick();
        if (tick != currentTick) {
            synchronized (ServerState.class) {
                if (tick != currentTick) {
                    PREVIOUS = CURRENT;
                    CURRENT = new PerTickMap();
                    currentTick = tick;
                }
            }
        }
        Entry entry = CURRENT.map.get(key);
        if (entry != null && entry.tick == tick) {
            return (T) entry.value;
        }
        T value = loader.load();
        CURRENT.map.put(key, new Entry(value, tick));
        return value;
    }

    /** Invalidate all cached entries (e.g., on /webinterface reload). */
    public static void invalidateAll() {
        CURRENT = new PerTickMap();
        PREVIOUS = new PerTickMap();
        currentTick = -1;
    }

    public static int getCacheSize() {
        return CURRENT.map.size() + PREVIOUS.map.size();
    }

    @FunctionalInterface
    public interface Loader<T> {
        T load();
    }
}
