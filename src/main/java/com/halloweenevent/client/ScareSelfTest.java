package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.EventData;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.server.EventManager;
import com.halloweenevent.server.Flashlights;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only automated jumpscare check, off unless started with {@code -Dhalloweenevent.scareSelfTest=true}
 * ({@code gradlew runClient -PscareTest}). Opens the "flashlight_test" save (use a copy), builds a flat arena at
 * night inside a zone of its own, then plays every scare the client can show (whisper, text, figure at each level,
 * chase, screen eyes, hypnosis, the hand grab with the screen hand, the Carry On and Freecam faces) and saves
 * screenshots as {@code screenshots/scare_*.png}, then quits.
 */
public final class ScareSelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("halloweenevent.scareSelfTest");

    private record Step(String name, Runnable start, int... shotsAt) {
        int length() {
            int max = 0;
            for (int s : shotsAt) max = Math.max(max, s);
            return max + 15;
        }
    }

    private static final int SETUP_AT = 60;
    private static final int START_AT = 200;

    private static final List<Step> STEPS = new ArrayList<>();
    private static int ticks;
    private static int stepIndex;
    private static int stepTicks;
    private static boolean done;

    static {
        STEPS.add(new Step("00_calm_fog", () -> {}, 20));
        STEPS.add(new Step("01_whisper_l3", () -> scare(S2CScare.WHISPER, 3), 30));
        for (int level = 1; level <= 3; level++) {
            int l = level;
            STEPS.add(new Step("02_text_l" + level, () -> scare(S2CScare.TEXT, l), 25, 60));
        }
        for (int level = 1; level <= 3; level++) {
            int l = level;
            STEPS.add(new Step("03_figure_l" + level, () -> scare(S2CScare.FIGURE, l), 20, 45, 70, 95));
        }
        for (int level : new int[]{1, 3}) {
            int l = level;
            STEPS.add(new Step("04_chase_l" + level, () -> scare(S2CScare.CHASE, l), 30, 80));
        }
        STEPS.add(new Step("05_eyes_swarm", () -> scare(S2CScare.EYES, 0), 25, 55));
        STEPS.add(new Step("06_eyes_wall", () -> scare(S2CScare.EYES, 1), 20, 35));
        STEPS.add(new Step("07_hypno", () -> scare(S2CScare.HYPNO, 0), 25, 55));
        STEPS.add(new Step("08_hand_grab", () -> {
            Minecraft mc = Minecraft.getInstance();
            ScareEffects.handGrab(mc.player.getId(), 1f, 0f);
        }, 8, 14, 22, 34));
        STEPS.add(new Step("09_carry_face", () -> scare(S2CScare.CARRY_ON, 0), 15, 40));
        STEPS.add(new Step("10_freecam_face", ScareEffects::freecamCaught, 6, 25));
        STEPS.add(new Step("11_after_stop", () -> scare(S2CScare.STOP, 0), 40));
    }

    private ScareSelfTest() {}

    private static void scare(int type, int level) {
        ScareEffects.handle(type, level);
    }

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
        aim(mc);
        if (stepIndex >= STEPS.size()) {
            done = true;
            HalloweenEventMod.LOGGER.info("[ScareTest] done, {} steps", STEPS.size());
            mc.stop();
            return;
        }
        Step step = STEPS.get(stepIndex);
        if (stepTicks == 0) {
            ScareEffects.stopAll();
            step.start().run();
            HalloweenEventMod.LOGGER.info("[ScareTest] step {}", step.name());
        }
        for (int shot : step.shotsAt()) {
            if (stepTicks == shot) {
                String name = String.format("scare_%s_t%03d.png", step.name(), shot);
                Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            }
        }
        if (++stepTicks >= step.length()) {
            stepIndex++;
            stepTicks = 0;
        }
    }

    private static void aim(Minecraft mc) {
        mc.player.setYRot(0f);
        mc.player.setXRot(8f);
        mc.player.yRotO = 0f;
        mc.player.xRotO = 8f;
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
            for (int dx = -20; dx <= 20; dx++) for (int dz = -25; dz <= 40; dz++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 0; dy <= 8; dy++) {
                    level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState());
                }
            }
            sp.setGameMode(GameType.CREATIVE);
            sp.teleportTo(level, x + 0.5, y, z + 0.5, 0f, 0f);
            Flashlights.randomKillsOff = true;
            if (!data.active) EventManager.start(server);
            HalloweenEventMod.LOGGER.info("[ScareTest] scene at {} {} {}", x, y, z);
        });
    }
}
