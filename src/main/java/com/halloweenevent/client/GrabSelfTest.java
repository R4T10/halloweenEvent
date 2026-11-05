package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.EventData;
import com.halloweenevent.gametest.Arena;
import com.halloweenevent.server.EventManager;
import com.halloweenevent.server.Flashlights;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.UUID;

/**
 * Dev-only automated hand-holding and grab check with a bot partner, off unless started with
 * {@code -Dhalloweenevent.grabSelfTest=true} ({@code gradlew runClient -PgrabTest}). Opens the "flashlight_test"
 * save (use a copy), builds a flat arena at night inside a zone, adds a bot player next to you and:
 * <ol>
 *   <li>takes your hand through Hold Hands' own code and photographs the pair from behind, in front and in
 *       first person;</li>
 *   <li>lets the dark figure grab and drag YOU away from the bot (third person, the figure and the screen hand,
 *       then the chase);</li>
 *   <li>lets it grab and drag the BOT away from you, as the partner sees it.</li>
 * </ol>
 * Screenshots go to {@code screenshots/grab_*.png}; the game quits at the end.
 */
public final class GrabSelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("halloweenevent.grabSelfTest");

    private static final int SETUP_AT = 60;
    private static final int START_AT = 200;

    private static int ticks;
    private static boolean done;
    private static UUID botId;
    private static double baseX, baseY, baseZ;
    private static float yaw;

    private GrabSelfTest() {}

    public static void clientTick(Minecraft mc) {
        if (!ENABLED || done || mc.player == null || mc.level == null) return;
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null && ticks > 20) mc.setScreen(null);
        if (ticks == SETUP_AT) setupScene(mc);
        if (ticks < START_AT) {
            if (ticks > SETUP_AT + 10) aim(mc);
            return;
        }
        int t = ticks - START_AT;
        aim(mc);

        // ---- A: holding hands, seen from behind, from the front and in first person ----
        if (t == 0) { yaw = 0f; reset(mc, 0f, 0f); camera(mc, CameraType.THIRD_PERSON_BACK); }
        if (t == 2) hold(mc);
        shot(mc, t, 30, "A1_hold_third_back");
        shot(mc, t, 50, "A2_hold_third_back");
        if (t == 55) camera(mc, CameraType.THIRD_PERSON_FRONT);
        shot(mc, t, 75, "A3_hold_third_front");
        if (t == 80) camera(mc, CameraType.FIRST_PERSON);
        shot(mc, t, 95, "A4_hold_first_person");

        // ---- B: the figure drags YOU away from the bot (the bot is east of you; you look at it) ----
        if (t == 100) { yaw = -90f; camera(mc, CameraType.THIRD_PERSON_BACK); }
        if (t == 102) grab(mc, false);
        for (int s : new int[]{8, 16, 24, 34, 48, 70, 110}) shot(mc, t, 100 + s, "B_you_dragged_t" + s);
        if (t == 240) { ScareEffects.stopAll(); yaw = 0f; reset(mc, 0f, 0f); camera(mc, CameraType.THIRD_PERSON_BACK); }
        if (t == 242) hold(mc);

        // ---- C: the figure drags the BOT away from you; you are the partner ----
        if (t == 260) yaw = -90f;
        if (t == 262) grab(mc, true);
        for (int s : new int[]{8, 16, 24, 34, 48, 70}) shot(mc, t, 260 + s, "C_bot_dragged_t" + s);
        if (t == 345) camera(mc, CameraType.THIRD_PERSON_FRONT);
        shot(mc, t, 355, "C_after_front");

        if (t == 100 || t == 215 || t == 255 || t == 340) logDistance(mc, "t" + t);

        if (t >= 370) {
            done = true;
            ScareEffects.stopAll();
            HalloweenEventMod.LOGGER.info("[GrabTest] done");
            mc.stop();
        }
    }

    private static void shot(Minecraft mc, int t, int at, String name) {
        if (t != at) return;
        Screenshot.grab(mc.gameDirectory, "grab_" + name + ".png", mc.getMainRenderTarget(), c -> {});
        HalloweenEventMod.LOGGER.info("[GrabTest] shot {}", name);
    }

    private static void camera(Minecraft mc, CameraType type) {
        mc.options.setCameraType(type);
    }

    private static void aim(Minecraft mc) {
        mc.player.setYRot(yaw);
        mc.player.setXRot(10f);
        mc.player.yRotO = yaw;
        mc.player.xRotO = 10f;
    }

    private static void grab(Minecraft mc, boolean victimIsBot) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID me = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer victim = server.getPlayerList().getPlayer(victimIsBot ? botId : me);
            String error = victim == null ? "no victim" : EventManager.testGrab(victim);
            HalloweenEventMod.LOGGER.info("[GrabTest] grab victim={} -> {}", victimIsBot ? "bot" : "you", error == null ? "started" : error);
        });
    }

    /** Logs where you and the bot stand and how far apart, read from the server. */
    private static void logDistance(Minecraft mc, String label) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID me = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(me);
            ServerPlayer bot = server.getPlayerList().getPlayer(botId);
            if (sp == null || bot == null) return;
            HalloweenEventMod.LOGGER.info("[GrabTest] {}: you ({}, {}) bot ({}, {}) apart {} blocks, holding {}", label,
                    Math.round(sp.getX() - baseX), Math.round(sp.getZ() - baseZ), Math.round(bot.getX() - baseX),
                    Math.round(bot.getZ() - baseZ), String.format("%.1f", sp.distanceTo(bot)), Arena.holdingEachOther(sp, bot));
        });
    }

    private static void hold(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID me = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(me);
            ServerPlayer bot = server.getPlayerList().getPlayer(botId);
            if (sp == null || bot == null) return;
            Arena.hold(sp, bot);
            HalloweenEventMod.LOGGER.info("[GrabTest] holding hands: {}", Arena.holdingEachOther(sp, bot));
        });
    }

    /** You at the scene's centre, the bot one block east of you, both facing {@code yawDeg}. */
    private static void reset(Minecraft mc, float yawDeg, float pitch) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID me = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(me);
            ServerPlayer bot = server.getPlayerList().getPlayer(botId);
            if (sp == null || bot == null) return;
            ServerLevel level = sp.serverLevel();
            sp.teleportTo(level, baseX, baseY, baseZ, yawDeg, pitch);
            bot.teleportTo(level, baseX + 1.0, baseY, baseZ, yawDeg, pitch);
            sp.setDeltaMovement(0, 0, 0);
            bot.setDeltaMovement(0, 0, 0);
            sp.setHealth(sp.getMaxHealth());
        });
    }

    private static void setupScene(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = mc.player.getUUID();
        mc.options.hideGui = false;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            ServerLevel level = sp.serverLevel();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.setDayTime(18000);
            EventData data = EventData.get(server);
            int x = sp.blockPosition().getX();
            int z = sp.blockPosition().getZ();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            data.dimension = level.dimension();
            data.setCorner(true, new BlockPos(x - 40, y - 10, z - 40));
            data.setCorner(false, new BlockPos(x + 40, y + 25, z + 60));
            data.scareLevels.put(id, 3);
            for (int dx = -25; dx <= 25; dx++) for (int dz = -25; dz <= 25; dz++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 0; dy <= 8; dy++) {
                    level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState());
                }
            }
            baseX = x + 0.5;
            baseY = y;
            baseZ = z + 0.5;
            sp.setGameMode(GameType.CREATIVE);
            sp.teleportTo(level, baseX, baseY, baseZ, 0f, 0f);
            Flashlights.randomKillsOff = true;
            if (!data.active) EventManager.start(server);
            ServerPlayer bot = Arena.createBot(server, level, "Partner");
            bot.teleportTo(level, baseX + 1.0, baseY, baseZ, 0f, 0f);
            botId = bot.getUUID();
            data.scareLevels.put(botId, 3);
            HalloweenEventMod.LOGGER.info("[GrabTest] scene at {} {} {}, bot placed", x, y, z);
        });
    }
}
