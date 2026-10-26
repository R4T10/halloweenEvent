package com.halloweenevent.server;

import com.halloweenevent.data.EventData;
import com.halloweenevent.network.Net;
import com.halloweenevent.network.S2CScare;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class HalloweenCommand {
    private HalloweenCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("halloween")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("admin").executes(ctx -> {
                    AdminActions.sendSnapshot(ctx.getSource().getPlayerOrException(), true);
                    return 1;
                }))
                .then(Commands.literal("start").executes(ctx -> {
                    String error = AdminActions.startError(EventData.get(ctx.getSource().getServer()));
                    if (error != null) {
                        ctx.getSource().sendFailure(Component.translatable(error));
                        return 0;
                    }
                    EventManager.start(ctx.getSource().getServer());
                    return 1;
                }))
                .then(Commands.literal("stop").executes(ctx -> {
                    EventManager.stop(ctx.getSource().getServer());
                    return 1;
                }))
                .then(Commands.literal("status").executes(HalloweenCommand::status))
                // Admin tests: trigger a scare right now instead of waiting for the timers.
                .then(Commands.literal("test")
                        .then(Commands.literal("figure")
                                .executes(ctx -> testFigure(ctx, self(ctx), 2))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
                                        .executes(ctx -> testFigure(ctx, self(ctx), IntegerArgumentType.getInteger(ctx, "level")))
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> testFigure(ctx, EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "level"))))))
                        .then(Commands.literal("chase")
                                .executes(ctx -> testChase(ctx, self(ctx)))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> testChase(ctx, EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("grab")
                                .executes(ctx -> testGrab(ctx, self(ctx)))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> testGrab(ctx, EntityArgument.getPlayer(ctx, "player")))))));
    }

    private static ServerPlayer self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static int testFigure(CommandContext<CommandSourceStack> ctx, ServerPlayer target, int level) {
        Net.toPlayer(target, new S2CScare(S2CScare.FIGURE, level));
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.test",
                "figure " + level, target.getDisplayName()), false);
        return 1;
    }

    private static int testChase(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        Net.toPlayer(target, new S2CScare(S2CScare.CHASE, 0));
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.test",
                "chase", target.getDisplayName()), false);
        return 1;
    }

    private static int testGrab(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        if (!EventManager.testGrab(target)) {
            ctx.getSource().sendFailure(Component.translatable("cmd.halloweenevent.test_grab_failed"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.test",
                "grab", target.getDisplayName()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        EventData d = EventData.get(ctx.getSource().getServer());
        int done = (int) d.progress.values().stream().filter(p -> p.completed).count();
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.status",
                Component.translatable(d.active ? "gui.halloweenevent.running" : "gui.halloweenevent.stopped"),
                d.spawns.size(), d.target, d.progress.size(), done), false);
        return 1;
    }
}
