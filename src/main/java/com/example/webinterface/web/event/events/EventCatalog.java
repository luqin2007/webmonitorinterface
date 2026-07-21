package com.example.webinterface.web.event.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of subscribable WebSocket events.
 *
 * <p>Every catalog entry is emitted by a Minecraft event listener. The catalog
 * is used by {@code GET /api/v1/events/catalog}.
 */
public final class EventCatalog {

    public static final List<String> EVENTS = List.of(
            "chat.public",
            "player.join",
            "player.quit"
    );

    public static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            entry("chat.public", "Fired for every public chat message sent in-game."),
            entry("player.join", "Fired when a player joins the server."),
            entry("player.quit", "Fired when a player disconnects from the server.")
    );

    private static Map.Entry<String, String> entry(String k, String v) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    public static List<String> getEvents() { return EVENTS; }

    public static String describe(String event) {
        return DESCRIPTIONS.getOrDefault(event, "");
    }
}
