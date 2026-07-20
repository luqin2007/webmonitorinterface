package com.example.webinterface.web.event;

import com.example.webinterface.web.handler.WebSocketHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicLong;

/** Event push manager with monotonic sequence numbers. */
public class EventPublisher {

    private static final Gson GSON = new Gson();
    private static final EventPublisher INSTANCE = new EventPublisher();
    private final AtomicLong sequence = new AtomicLong(0);

    private EventPublisher() {}

    public static EventPublisher getInstance() { return INSTANCE; }

    public void push(String eventName, JsonObject data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "event");
        payload.addProperty("event", eventName);
        payload.addProperty("ts", System.currentTimeMillis());
        payload.addProperty("seq", sequence.incrementAndGet());
        payload.add("data", data == null ? new JsonObject() : data);
        WebSocketHandler.broadcast(GSON.toJson(payload));
    }
}
