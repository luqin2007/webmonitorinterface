package com.example.webinterface.web.auth;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Token-bucket rate limiter.
 * Threads replenish lazily on each acquire: capacity tokens refill at `rate`
 * tokens per second up to `capacity`.
 */
public final class TokenBucket {
    private final double capacity;
    private final double ratePerSec;
    private final AtomicReference<State> stateRef;

    private record State(double tokens, long lastRefillNanos) {}

    public TokenBucket(double capacity, double ratePerSec) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (ratePerSec <= 0) throw new IllegalArgumentException("ratePerSec must be > 0");
        this.capacity = capacity;
        this.ratePerSec = ratePerSec;
        this.stateRef = new AtomicReference<>(new State(capacity, System.nanoTime()));
    }

    /** Try to acquire 1 token. Returns true if allowed (and consumes one). */
    public boolean tryAcquire() {
        return tryAcquire(1.0);
    }

    /** Try to acquire `permits` tokens atomically. Returns true if allowed. */
    public boolean tryAcquire(double permits) {
        if (permits <= 0) return true;
        while (true) {
            State current = stateRef.get();
            long now = System.nanoTime();
            double elapsedSec = (now - current.lastRefillNanos) / 1_000_000_000.0;
            double refilled = current.tokens + elapsedSec * ratePerSec;
            double available = Math.min(capacity, refilled);
            if (available < permits) return false;
            State next = new State(available - permits, now);
            if (stateRef.compareAndSet(current, next)) return true;
        }
    }

    public double getAvailableTokens() {
        State current = stateRef.get();
        long now = System.nanoTime();
        double elapsedSec = (now - current.lastRefillNanos) / 1_000_000_000.0;
        return Math.min(capacity, current.tokens + elapsedSec * ratePerSec);
    }

    public double getCapacity() { return capacity; }
    public double getRatePerSec() { return ratePerSec; }
}
