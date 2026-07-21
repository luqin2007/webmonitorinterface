package com.example.webinterface.web;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs world access on the Minecraft server thread and returns immutable JSON snapshots. */
public final class MinecraftThread {
    private static final long TIMEOUT_SECONDS = 10;

    private MinecraftThread() {}

    public static <T> T call(ThrowingSupplier<T> supplier) throws Exception {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isSameThread()) return supplier.get();

        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(supplier.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
