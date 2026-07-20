package com.example.webinterface.event;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.web.event.EventPublisher;
import com.google.gson.JsonObject;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Minecraft 事件监听器
 * 监听游戏内事件，转发给 WebSocket EventPublisher
 */
public class MinecraftEventListener {

    private final EventPublisher publisher = EventPublisher.getInstance();

    /**
     * 聊天消息监听
     */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getUsername());
        data.addProperty("uuid", event.getPlayer().getUUID().toString());
        data.addProperty("message", event.getMessage().getString());
        data.addProperty("timestamp", System.currentTimeMillis());

        publisher.pushRaw("chat.public", data.toString());
        WebInterfaceMod.LOGGER.debug("[Event] Chat: {}: {}", event.getUsername(), event.getMessage().getString());
    }

    /**
     * 玩家加入监听
     */
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getEntity().getName().getString());
        data.addProperty("uuid", event.getEntity().getUUID().toString());
        data.addProperty("timestamp", System.currentTimeMillis());

        publisher.pushRaw("player.join", data.toString());
    }

    /**
     * 玩家退出监听
     */
    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getEntity().getName().getString());
        data.addProperty("uuid", event.getEntity().getUUID().toString());
        data.addProperty("timestamp", System.currentTimeMillis());

        publisher.pushRaw("player.quit", data.toString());
    }
}