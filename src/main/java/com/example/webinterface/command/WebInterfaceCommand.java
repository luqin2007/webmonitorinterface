package com.example.webinterface.command;

import com.example.webinterface.WebInterfaceMod;
import com.example.webinterface.WebInterfaceMod.WebInterfaceServices;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * {@code /webinterface} command - reload, status, sessions, invalidate-cache.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code reload}            - hot reload config (RegenAuth + MethodGuard).</li>
 *   <li>{@code status}            - server side status (port, sessions, cached entries).</li>
 *   <li>{@code invalidate-cache}  - drop all 1-tick read caches.</li>
 * </ul>
 *
 * <p>Requires op (permission level 4) - enforced by Commands.literal().requires(...).
 */
public final class WebInterfaceCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("webinterface")
                        .requires(src -> src.hasPermission(4))
                        .then(Commands.literal("reload")
                                .executes(this::reload))
                        .then(Commands.literal("status")
                                .executes(this::status))
                        .then(Commands.literal("invalidate-cache")
                                .executes(this::invalidateCache))
        );
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        WebInterfaceServices svc = WebInterfaceMod.getServices();
        if (svc == null) {
            ctx.getSource().sendFailure(Component.literal("[WebInterface] not initialized yet"));
            return 0;
        }
        try {
            svc.reload();
            ctx.getSource().sendSuccess(() -> Component.literal("[WebInterface] config reloaded"), true);
            return 1;
        } catch (Exception e) {
            WebInterfaceMod.LOGGER.error("[WebInterface] reload failed", e);
            ctx.getSource().sendFailure(Component.literal("[WebInterface] reload failed: " + e.getMessage()));
            return 0;
        }
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        WebInterfaceServices svc = WebInterfaceMod.getServices();
        if (svc == null) {
            ctx.getSource().sendFailure(Component.literal("[WebInterface] not initialized yet"));
            return 0;
        }
        int sessions = svc.getActiveSessionCount();
        int cache = svc.getCacheSize();
        boolean running = svc.isWebServerRunning();
        int port = svc.getPort();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("[WebInterface] running=%s port=%d sessions=%d cache_entries=%d",
                        running, port, sessions, cache)), false);
        return 1;
    }

    private int invalidateCache(CommandContext<CommandSourceStack> ctx) {
        WebInterfaceServices svc = WebInterfaceMod.getServices();
        if (svc == null) {
            ctx.getSource().sendFailure(Component.literal("[WebInterface] not initialized yet"));
            return 0;
        }
        int dropped = svc.getCacheSize();
        svc.invalidateCache();
        ctx.getSource().sendSuccess(() ->
                Component.literal("[WebInterface] dropped " + dropped + " cache entries"), false);
        return 1;
    }
}
