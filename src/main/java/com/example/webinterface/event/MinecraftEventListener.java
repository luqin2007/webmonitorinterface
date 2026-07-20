package com.example.webinterface.event;

import com.example.webinterface.WebMonitorMod;
import com.example.webinterface.web.event.EventPublisher;
import com.google.gson.JsonObject;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forwards a small set of Minecraft events to WebSocket subscribers. */
public class MinecraftEventListener {

    private final EventPublisher publisher = EventPublisher.getInstance();

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getUsername());
        data.addProperty("uuid", event.getPlayer().getUUID().toString());
        data.addProperty("message", event.getMessage().getString());
        data.addProperty("timestamp", System.currentTimeMillis());
        publisher.push("chat.public", data);
        WebMonitorMod.LOGGER.debug("[Event] Chat: {}: {}", event.getUsername(), event.getMessage().getString());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getEntity().getName().getString());
        data.addProperty("uuid", event.getEntity().getUUID().toString());
        data.addProperty("timestamp", System.currentTimeMillis());
        publisher.push("player.join", data);
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getEntity().getName().getString());
        data.addProperty("uuid", event.getEntity().getUUID().toString());
        data.addProperty("timestamp", System.currentTimeMillis());
        publisher.push("player.quit", data);
    }
}
