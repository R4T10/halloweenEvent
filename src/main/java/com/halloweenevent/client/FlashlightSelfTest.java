package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.EventData;
import com.halloweenevent.item.FlashlightItem;
import com.halloweenevent.registry.ModItems;
import com.halloweenevent.server.Flashlights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Dev-only automated flashlight check, off unless started with {@code -Dhalloweenevent.flashlightSelfTest=true}
 * ({@code gradlew runClient -PflashlightTest}). In the world it was opened with (use a copy: it builds blocks):
 * builds a small scene (flat ground, a one-block ledge, a wall), switches a steady flashlight on at night, turns
 * the camera through {@link #ANGLES} and saves one screenshot per debug view as {@code screenshots/fltest_*.png},
 * then quits.
 */
public final class FlashlightSelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("halloweenevent.flashlightSelfTest");

    /** yaw, pitch. Yaw 0 looks along +Z, into the scene. */
    private static final float[][] ANGLES = {
            {0, 5}, {0, 15}, {0, 30}, {0, 60}, {25, 8}, {-25, 8}, {40, 18}, {-60, 12}, {90, 10}, {180, 12}, {0, -15},
    };
    private static final int VIEWS = 4;
    private static final int SETUP_AT = 60;
    private static final int START_AT = 300;
    private static final int TICKS_PER_SHOT = 12;

    private static int ticks;
    private static boolean done;

    private FlashlightSelfTest() {}

    public static void clientTick(Minecraft mc) {
        if (!ENABLED || done || mc.player == null || mc.level == null) return;
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null && ticks > 20) mc.setScreen(null);
        if (ticks == SETUP_AT) setupScene(mc);
        if (ticks > SETUP_AT + 10 && ticks < START_AT) aim(mc, ANGLES[1][0], ANGLES[1][1]);
        // Pure vanilla frame (no zone fog, flashlight off) to tell our passes apart from the world itself.
        if (ticks == START_AT - 60) ZoneFogRenderer.testDisabled = true;
        if (ticks == START_AT - 50) Screenshot.grab(mc.gameDirectory, "fltest_vanilla.png", mc.getMainRenderTarget(), c -> {});
        if (ticks == START_AT - 45) ZoneFogRenderer.testDisabled = false;
        if (ticks == START_AT - 20) {
            // Zone fog alone, flashlight still off (as handed out).
            Screenshot.grab(mc.gameDirectory, "fltest_off_fog.png", mc.getMainRenderTarget(), c -> {});
        }
        if (ticks == START_AT - 15) switchOnHandedOut(mc);
        if (ticks < START_AT) return;

        int shot = (ticks - START_AT) / TICKS_PER_SHOT;
        int at = (ticks - START_AT) % TICKS_PER_SHOT;
        if (shot >= ANGLES.length * VIEWS) {
            FlashlightRenderer.setDebugView(0);
            int t = ticks - START_AT - ANGLES.length * VIEWS * TICKS_PER_SHOT;
            if (t < 30) {
                // Depth as the flashlight sees it, without the fog drawn over it.
                ZoneFogRenderer.testDisabled = t < 25;
                aim(mc, ANGLES[1][0], ANGLES[1][1]);
                FlashlightRenderer.setDebugView(t < 25 ? 3 : 0);
                if (t == 20) Screenshot.grab(mc.gameDirectory, "fltest_depth_nofog.png", mc.getMainRenderTarget(), c -> {});
                return;
            }
            t -= 30;
            if (t < THIRD_PERSON_TICKS) {
                thirdPerson(mc, t);
                return;
            }
            if (inventoryChecks(mc, t - THIRD_PERSON_TICKS)) {
                done = true;
                HalloweenEventMod.LOGGER.info("[FlashlightTest] done, {} screenshots", ANGLES.length * VIEWS);
                mc.stop();
            }
            return;
        }
        float[] a = ANGLES[shot / VIEWS];
        int view = shot % VIEWS;
        if (at == 0) {
            aim(mc, a[0], a[1]);
            FlashlightRenderer.setDebugView(view);
            FlashlightRenderer.requestSummary();
        } else if (at == TICKS_PER_SHOT - 2) {
            String name = String.format("fltest_%02d_yaw%d_pitch%d_v%d.png", shot / VIEWS, (int) a[0], (int) a[1], view);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            HalloweenEventMod.LOGGER.info("[FlashlightTest] shot {}", name);
        } else {
            aim(mc, a[0], a[1]);
        }
    }

    private static final int THIRD_PERSON_TICKS = 60;

    /** How other players see it: the beam from the off hand in third person, from behind and from the front. */
    private static void thirdPerson(Minecraft mc, int t) {
        if (t == 0) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        if (t == 30) mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        aim(mc, 20, 25);
        if (t == 25) Screenshot.grab(mc.gameDirectory, "fltest_third_back.png", mc.getMainRenderTarget(), c -> {});
        if (t == 55) {
            Screenshot.grab(mc.gameDirectory, "fltest_third_front.png", mc.getMainRenderTarget(), c -> {});
        }
        if (t == THIRD_PERSON_TICKS - 1) mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
    }

    /** The flashlight the zone handed out (off hand, off): switched on the way a right-click does it. */
    private static void switchOnHandedOut(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            ItemStack offhand = sp.getOffhandItem();
            check("handed out into the off hand, switched off", FlashlightItem.is(offhand) && !FlashlightItem.isOn(offhand));
            Flashlights.toggle(sp, offhand);
        });
    }

    /**
     * Server-side ownership checks, one step every 15 ticks (the inventory sync runs every 5): toss, duplicate,
     * leave the zone, come back. Logs PASS/FAIL per step. @return true when finished
     */
    private static boolean inventoryChecks(Minecraft mc, int t) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || t % 15 != 0) return t > 15 * 7;
        UUID id = mc.player.getUUID();
        int step = t / 15;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            AABB zone = EventData.get(server).zone();
            if (sp == null || zone == null) return;
            switch (step) {
                case 0 -> check("in zone: exactly one", count(sp) == 1);
                case 1 -> {
                    ItemStack held = sp.getOffhandItem();
                    sp.getInventory().offhand.set(0, ItemStack.EMPTY);
                    sp.drop(held, true);
                }
                case 2 -> check("tossed: still exactly one, none on the ground", count(sp) == 1 && groundItems(sp) == 0);
                case 3 -> sp.getInventory().add(new ItemStack(ModItems.FLASHLIGHT.get()));
                case 4 -> check("duplicate removed", count(sp) == 1);
                case 5 -> sp.teleportTo(sp.serverLevel(), zone.maxX + 6, sp.getY(), zone.maxZ + 6, 0f, 0f);
                case 6 -> {
                    check("left the zone: none", count(sp) == 0);
                    sp.teleportTo(sp.serverLevel(), zone.getCenter().x, sp.getY(), zone.getCenter().z, 0f, 0f);
                }
                case 7 -> check("back in the zone: exactly one again", count(sp) == 1);
                default -> {}
            }
        });
        return false;
    }

    private static int count(ServerPlayer sp) {
        int n = FlashlightItem.is(sp.containerMenu.getCarried()) ? 1 : 0;
        for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            if (FlashlightItem.is(sp.getInventory().getItem(i))) n++;
        }
        return n;
    }

    private static int groundItems(ServerPlayer sp) {
        return sp.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                sp.getBoundingBox().inflate(8), e -> FlashlightItem.is(e.getItem())).size();
    }

    private static void check(String what, boolean ok) {
        HalloweenEventMod.LOGGER.info("[FlashlightTest] {} {}", ok ? "PASS" : "FAIL", what);
    }

    private static void aim(Minecraft mc, float yaw, float pitch) {
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setYHeadRot(yaw);
        mc.player.yHeadRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    /** Night, a steady lit flashlight in hand, and a scene in front of the player (inside the zone if one is set). */
    private static void setupScene(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = mc.player.getUUID();
        FlashlightClient.steady = true;
        mc.options.hideGui = false;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            ServerLevel level = sp.serverLevel();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.setDayTime(18000);
            AABB zone = EventData.get(server).zone();
            int x = Mth.floor(zone != null ? zone.getCenter().x : sp.getX());
            int z = Mth.floor(zone != null ? zone.getCenter().z : sp.getZ());
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int dx = -8; dx <= 8; dx++) for (int dz = -3; dz <= 26; dz++) for (int dy = 0; dy <= 5; dy++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState());
            }
            for (int dz = 3; dz <= 24; dz++) {
                level.setBlockAndUpdate(new BlockPos(x - 4, y, z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x - 5, y, z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
            for (int dx = 1; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + 9), Blocks.STONE.defaultBlockState());
            }
            sp.setGameMode(GameType.CREATIVE);
            sp.teleportTo(level, x + 0.5, y, z + 0.5, 0f, 0f);
            // Start like a new player: no flashlight left from earlier play, free off hand for the hand-out.
            Flashlights.randomKillsOff = true;
            Flashlights.strip(sp);
            sp.getInventory().offhand.set(0, ItemStack.EMPTY);
            HalloweenEventMod.LOGGER.info("[FlashlightTest] scene at {} {} {}, zone {}", x, y, z, zone);
        });
    }
}
