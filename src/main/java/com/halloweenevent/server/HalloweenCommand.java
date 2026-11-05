package com.halloweenevent.server;

import com.halloweenevent.data.EventData;
import com.halloweenevent.network.Net;
import com.halloweenevent.network.S2CScare;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/**
 * {@code /halloween} (operators only). Every scare and flashlight effect can be fired at chosen players right now,
 * without the timers, the zone or a running event: {@code /halloween test <effect> [targets]}, also spelled
 * {@code /halloween prank ...}. Targets are any player selector (a name, {@code @a}, {@code @r}, {@code @p}); with
 * none, the effect hits whoever runs the command.
 */
public final class HalloweenCommand {
    /** One effect on one player. @return null when it fired, otherwise a translation key saying why not */
    private interface Effect {
        String apply(ServerPlayer target);
    }

    private HalloweenCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("halloween")
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
                .then(Commands.literal("reset").executes(ctx -> {
                    EventManager.resetProgress(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.translatable("msg.halloweenevent.admin.reset"), true);
                    return 1;
                }))
                // Per-player settings, also handy for testing: scare level and candy count.
                .then(Commands.literal("fear")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
                                        .executes(ctx -> setFear(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                IntegerArgumentType.getInteger(ctx, "level"))))))
                .then(Commands.literal("candy")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, 256))
                                        .executes(ctx -> setCandy(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(tests()));
        // Same tree under a second name: "/halloween prank eyes wall Steve".
        dispatcher.register(Commands.literal("halloween").requires(src -> src.hasPermission(2))
                .then(Commands.literal("prank").redirect(root.getChild("test"))));
    }

    /** {@code test <effect> [targets]}: every scare and flashlight effect, on demand. */
    private static LiteralArgumentBuilder<CommandSourceStack> tests() {
        return Commands.literal("test")
                // The hold-hands ladder, step by step (level 1-3 like the player setting; default: their own).
                .then(leveled("whisper", S2CScare.WHISPER))
                .then(leveled("text", S2CScare.TEXT))
                .then(leveled("figure", S2CScare.FIGURE))
                .then(leveled("chase", S2CScare.CHASE))
                // Screen scares.
                .then(targeted("eyes", "eyes", p -> scare(p, S2CScare.EYES, 0))
                        .then(targeted("wall", "eyes wall", p -> scare(p, S2CScare.EYES, 1))))
                .then(targeted("hypno", "hypno", p -> scare(p, S2CScare.HYPNO, 0)))
                .then(targeted("face", "face", p -> scare(p, S2CScare.FACE, 0)))
                .then(targeted("freecam", "freecam", p -> scare(p, S2CScare.FREECAM_FACE, 0)))
                // The dark figure drags a hand-holding player away from their partner; "alone" needs no partner.
                .then(targeted("grab", "grab", EventManager::testGrab)
                        .then(targeted("alone", "grab alone", EventManager::testGrabAlone)))
                // The event flashlight: kill it, kill it so the next switch-on gives up, or make it flicker.
                .then(targeted("flashlight", "flashlight", p -> Flashlights.kill(p) ? null : "cmd.halloweenevent.test_flashlight_failed")
                        .then(targeted("jinx", "flashlight jinx", p -> Flashlights.jinx(p) ? null : "cmd.halloweenevent.test_no_flashlight"))
                        .then(Commands.literal("flicker")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                        .executes(ctx -> run(ctx, List.of(self(ctx)), "flashlight flicker", flicker(ctx)))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                        "flashlight flicker", flicker(ctx)))))))
                // Ends every scare on their screen at once.
                .then(targeted("clear", "clear", p -> scare(p, S2CScare.CLEAR, 0)));
    }

    /** {@code <name> [targets]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> targeted(String literal, String name, Effect effect) {
        return Commands.literal(literal)
                .executes(ctx -> run(ctx, List.of(self(ctx)), name, effect))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), name, effect)));
    }

    /**
     * {@code <name> [level] [targets]}: without a level, each target's own scare level. The level argument comes
     * first so "figure 3" reads 3 as the level, not as a player called "3".
     */
    private static LiteralArgumentBuilder<CommandSourceStack> leveled(String name, int type) {
        return Commands.literal(name)
                .executes(ctx -> run(ctx, List.of(self(ctx)), name, p -> scare(p, type, levelOf(p))))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 3))
                        .executes(ctx -> {
                            int level = IntegerArgumentType.getInteger(ctx, "level");
                            return run(ctx, List.of(self(ctx)), name + " " + level, p -> scare(p, type, level));
                        })
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> {
                                    int level = IntegerArgumentType.getInteger(ctx, "level");
                                    return run(ctx, EntityArgument.getPlayers(ctx, "targets"), name + " " + level,
                                            p -> scare(p, type, level));
                                })))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), name,
                                p -> scare(p, type, levelOf(p)))));
    }

    private static Effect flicker(CommandContext<CommandSourceStack> ctx) {
        int ticks = IntegerArgumentType.getInteger(ctx, "seconds") * 20;
        return p -> Flashlights.flicker(p, ticks) ? null : "cmd.halloweenevent.test_no_flashlight";
    }

    /** Applies the effect to every target; reports each failure, and one success line. @return how many it hit */
    private static int run(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, String name, Effect effect) {
        int hit = 0;
        ServerPlayer last = null;
        for (ServerPlayer p : targets) {
            String error = effect.apply(p);
            if (error != null) {
                ctx.getSource().sendFailure(Component.translatable(error, p.getDisplayName()));
            } else {
                hit++;
                last = p;
            }
        }
        if (hit == 1) {
            Component who = last.getDisplayName();
            ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.test", name, who), false);
        } else if (hit > 1) {
            int n = hit;
            ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.test_many", name, n), false);
        }
        return hit;
    }

    private static String scare(ServerPlayer p, int type, int level) {
        Net.toPlayer(p, new S2CScare(type, level));
        return null;
    }

    private static int levelOf(ServerPlayer p) {
        return EventData.get(p.server).levelFor(p.getUUID());
    }

    private static ServerPlayer self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static int setFear(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, int level) {
        EventData d = EventData.get(ctx.getSource().getServer());
        for (ServerPlayer p : targets) d.scareLevels.put(p.getUUID(), level);
        d.setDirty();
        int n = targets.size();
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.fear", n, level), true);
        return n;
    }

    /** Sets the candy count (clamped to the target); reaching it shows "escape the zone", walking out still wins. */
    private static int setCandy(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, int count) {
        EventData d = EventData.get(ctx.getSource().getServer());
        int value = Math.min(count, d.target);
        for (ServerPlayer p : targets) {
            d.progress(p).count = value;
            EventManager.sendStatus(p, d);
        }
        d.setDirty();
        int n = targets.size();
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.halloweenevent.candy", n, value, d.target), true);
        return n;
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
