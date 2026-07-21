package com.example.webinterface.command;

import com.example.webinterface.WebMonitorMod;
import com.example.webinterface.security.ApiKey;
import com.example.webinterface.security.KeyManager;
import com.example.webinterface.web.WebServer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * In-game commands:
 * <ul>
 *   <li>{@code /webmonitor server start [<port>]}</li>
 *   <li>{@code /webmonitor server stop}</li>
 *   <li>{@code /webmonitor key}</li>
 *   <li>{@code /webmonitor key generate [<comment>]}</li>
 *   <li>{@code /webmonitor key delete <key>}</li>
 * </ul>
 */
public final class WebMonitorCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(buildRoot("webmonitor"));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        return Commands.literal(name)
                .then(Commands.literal("server")
                        .requires(src -> src.hasPermission(4))
                        .then(Commands.literal("start")
                                .executes(this::serverStartDefault)
                                .then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
                                        .executes(this::serverStartPort)))
                        .then(Commands.literal("stop")
                                .executes(this::serverStop)))
                .then(Commands.literal("key")
                        .requires(src -> src.getEntity() instanceof ServerPlayer)
                        .executes(this::keyList)
                        .then(Commands.literal("generate")
                                .executes(ctx -> keyGenerate(ctx, ""))
                                .then(Commands.argument("comment", StringArgumentType.greedyString())
                                        .executes(ctx -> keyGenerate(ctx, StringArgumentType.getString(ctx, "comment")))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(this::keyDelete))));
    }

    private int serverStartDefault(CommandContext<CommandSourceStack> ctx) {
        int port = WebMonitorMod.getConfig() == null ? 18080 : WebMonitorMod.getConfig().getPort();
        return doStart(ctx, port);
    }

    private int serverStartPort(CommandContext<CommandSourceStack> ctx) {
        return doStart(ctx, IntegerArgumentType.getInteger(ctx, "port"));
    }

    private int doStart(CommandContext<CommandSourceStack> ctx, int port) {
        WebServer server = WebMonitorMod.ensureWebServer();
        if (server == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] not initialized"));
            return 0;
        }
        boolean wasRunning = server.isRunning();
        boolean ok = server.start(port);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] failed to start on port " + port));
            return 0;
        }
        String msg = wasRunning
                ? "[WebMonitor] restarted on port " + port
                : "[WebMonitor] started on port " + port;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private int serverStop(CommandContext<CommandSourceStack> ctx) {
        WebServer server = WebMonitorMod.getWebServer();
        if (server == null || !server.isRunning()) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] server is not running"));
            return 0;
        }
        server.stop();
        ctx.getSource().sendSuccess(() -> Component.literal("[WebMonitor] stopped"), true);
        return 1;
    }

    private int keyList(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] players only"));
            return 0;
        }
        KeyManager km = WebMonitorMod.getKeyManager();
        if (km == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] key manager not ready"));
            return 0;
        }
        List<ApiKey> keys = km.listByOwner(player.getUUID());
        if (keys.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[WebMonitor] no keys. Use /webmonitor key generate"), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[WebMonitor] your keys (" + keys.size() + "):"), false);
        for (ApiKey k : keys) {
            MutableComponent line = Component.literal("  ")
                    .append(clickableKey(k.getKey()))
                    .append(Component.literal(k.getComment().isBlank() ? "" : "  // " + k.getComment())
                            .withStyle(ChatFormatting.GRAY));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private int keyGenerate(CommandContext<CommandSourceStack> ctx, String comment) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] players only"));
            return 0;
        }
        KeyManager km = WebMonitorMod.getKeyManager();
        if (km == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] key manager not ready"));
            return 0;
        }
        ApiKey key = km.generate(player.getUUID(), player.getGameProfile().getName(), comment);
        MutableComponent msg = Component.literal("[WebMonitor] new key: ")
                .append(clickableKey(key.getKey()))
                .append(Component.literal("  (click to copy)").withStyle(ChatFormatting.GRAY));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int keyDelete(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] players only"));
            return 0;
        }
        String key = StringArgumentType.getString(ctx, "key");
        KeyManager km = WebMonitorMod.getKeyManager();
        if (km == null) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] key manager not ready"));
            return 0;
        }
        boolean ok = km.deleteOwned(key, player.getUUID());
        if (!ok) {
            // OP may delete any key
            if (ctx.getSource().hasPermission(4)) {
                ok = km.delete(key);
            }
        }
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("[WebMonitor] key not found or not owned by you"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[WebMonitor] key deleted"), false);
        return 1;
    }

    private static MutableComponent clickableKey(String key) {
        return Component.literal(key)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, key))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to copy"))));
    }
}
