package com.example.webinterface.web.event;

import com.example.webinterface.web.handler.WebSocketHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件推送管理器
 * 支持客户端订阅/取消订阅事件，事件触发时通过 WebSocket 广播
 * 事件名支持通配符 * 匹配
 */
public class EventPublisher {

    private static final Gson GSON = new Gson();
    private static final EventPublisher INSTANCE = new EventPublisher();

    /** 事件序号生成器 */
    private final AtomicLong sequence = new AtomicLong(0);

    private EventPublisher() {}

    public static EventPublisher getInstance() {
        return INSTANCE;
    }

    /**
     * 推送事件到所有已订阅该事件的连接
     */
    public void push(String eventName, JsonObject data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "event");
        payload.addProperty("event", eventName);
        payload.addProperty("ts", System.currentTimeMillis());
        payload.addProperty("seq", sequence.incrementAndGet());
        payload.add("data", data);

        String json = GSON.toJson(payload);
        WebSocketHandler.broadcast(json);
    }

    /**
     * 快速推送事件（传入已序列化的 JSON）
     */
    public void pushRaw(String eventName, String dataJson) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "event");
        payload.addProperty("event", eventName);
        payload.addProperty("ts", System.currentTimeMillis());
        payload.addProperty("seq", sequence.incrementAndGet());
        try {
            JsonObject data = GSON.fromJson(dataJson, JsonObject.class);
            payload.add("data", data);
        } catch (Exception e) {
            payload.addProperty("data", dataJson);
        }
        WebSocketHandler.broadcast(GSON.toJson(payload));
    }
}
