package com.example.webinterface.security;

import java.util.UUID;

/** Immutable API key record owned by a player. */
public final class ApiKey {
    private final String key;
    private final UUID ownerUuid;
    private final String ownerName;
    private final String comment;
    private final long createdAt;

    public ApiKey(String key, UUID ownerUuid, String ownerName, String comment, long createdAt) {
        this.key = key;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.comment = comment == null ? "" : comment;
        this.createdAt = createdAt;
    }

    public String getKey() { return key; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public String getComment() { return comment; }
    public long getCreatedAt() { return createdAt; }
}
