package com.example.webinterface.web.event.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of subscribable WebSocket events.
 *
 * <p>The actual <em>emission</em> of most events is the responsibility of
 * Minecraft event listeners (some not yet wired up). The catalog itself is used
 * by {@code GET /api/v1/events/catalog} and by WS subscribe validation.
 */
public final class EventCatalog {

    public static final List<String> EVENTS = List.of(
            "world.block.update",
            "world.entity.spawn",
            "world.entity.despawn",
            "world.entity.move",
            "world.player.death",
            "world.player.respawn",
            "chat.public",
            "player.join",
            "player.quit",
            "server.starting",
            "server.started",
            "server.stopping",
            "server.tps",
            "capability.changed",
            "batch.progress"
    );

    public static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            entry("world.block.update", "Fired when a block state changes (high volume, throttled)."),
            entry("world.entity.spawn", "Fired when any entity spawns in any loaded world."),
            entry("world.entity.despawn", "Fired when any entity despawns or is removed."),
            entry("world.entity.move", "High-frequency entity motion events. Subscribe with care."),
            entry("world.player.death", "Fired when any player dies."),
            entry("world.player.respawn", "Fired when any player respawns."),
            entry("chat.public", "Fired for every public chat message sent in-game."),
            entry("player.join", "Fired when a player joins the server."),
            entry("player.quit", "Fired when a player disconnects from the server."),
            entry("server.starting", "Fired once at server startup (before players can join)."),
            entry("server.started", "Fired once when the server is ready to accept players."),
            entry("server.stopping", "Fired once when the server begins shutdown."),
            entry("server.tps", "Periodic TPS push (interval controlled by websocket.tps_update_interval)."),
            entry("capability.changed", "Fired when a tracked capability value changes past configured thresholds."),
            entry("batch.progress", "Fired while a long-running batch query is in flight.")
    );

    private static Map.Entry<String, String> entry(String k, String v) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    public static List<String> getEvents() { return EVENTS; }

    public static String describe(String event) {
        return DESCRIPTIONS.getOrDefault(event, "");
    }
}
