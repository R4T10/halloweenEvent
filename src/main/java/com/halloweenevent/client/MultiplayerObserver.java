package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.item.FlashlightItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Dev-only player stand-in for multiplayer runs, off unless started with {@code -Dhalloweenevent.mpObserver=<name>}
 * ({@code gradlew runClient -PmpUser=<name>}); it joins 127.0.0.1 by itself from the title screen. The server side is driven over RCON;
 * this client only does what a player would: keeps no menu open, respawns, faces the nearest other player,
 * switches the event flashlight on whenever it is off (right-click, so a killed one struggles first), and saves a
 * screenshot plus a state line every {@value #EVERY} ticks. Quits when the server lets it go.
 */
public final class MultiplayerObserver {
    public static final String NAME = System.getProperty("halloweenevent.mpObserver", "");
    public static final boolean ENABLED = !NAME.isEmpty();

    private static final int EVERY = 60;
    /** Wait this long with the flashlight off before switching it back on (lets a kill show first). */
    private static final int RELIGHT_AFTER = 50;

    private static boolean joined;
    private static int ticks;
    private static int offFor;
    private static int shots;

    private MultiplayerObserver() {}

    private static final String SERVER = "127.0.0.1:25565";
    /** Title screen time before connecting: quick-play connected while mods were still loading and hung the handshake. */
    private static final int CONNECT_AFTER = 160;
    private static final int MAX_TRIES = 5;
    private static int menuTicks;
    private static int tries;

    /**
     * Two dev clients logging in at the same moment left one of them without any Forge handshake packet until the
     * server dropped it; so each name waits a different time (PlayerA, PlayerB: 6 s apart).
     */
    private static final int STAGGER = NAME.chars().sum() % 5 * 120;
    /** A connect screen still up after this long is a hung login: cancel and retry. */
    private static final int CONNECT_TIMEOUT = 300;
    private static int connectingTicks;

    /** Called every client tick while not in a world: connects from the title screen, retries after a failure. */
    public static void menuTick(Minecraft mc) {
        if (!ENABLED || joined || mc.level != null) return;
        if (mc.screen instanceof ConnectScreen) {
            if (++connectingTicks >= CONNECT_TIMEOUT) {
                connectingTicks = 0;
                HalloweenEventMod.LOGGER.info("[MP {}] login hung for {} s, cancelling", NAME, CONNECT_TIMEOUT / 20);
                mc.setScreen(new TitleScreen());
            }
            return;
        }
        connectingTicks = 0;
        boolean idle = mc.screen instanceof TitleScreen || mc.screen instanceof DisconnectedScreen;
        if (!idle) {
            menuTicks = 0;
            return;
        }
        if (++menuTicks < CONNECT_AFTER + (tries == 0 ? STAGGER : 0) || tries >= MAX_TRIES) return;
        menuTicks = 0;
        tries++;
        HalloweenEventMod.LOGGER.info("[MP {}] connecting to {} (try {}/{})", NAME, SERVER, tries, MAX_TRIES);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(SERVER),
                new ServerData("mp test", SERVER, false), false);
    }

    /** The server let us go (kick or stop): close the game so the run ends by itself. */
    public static void onLoggedOut(Minecraft mc) {
        if (!ENABLED || !joined) return;
        joined = false;
        HalloweenEventMod.LOGGER.info("[MP {}] disconnected, quitting", NAME);
        mc.stop();
    }

    /** Called from the client tick, only while in a world. */
    public static void clientTick(Minecraft mc) {
        if (!ENABLED) return;
        if (!joined) {
            joined = true;
            HalloweenEventMod.LOGGER.info("[MP {}] joined", NAME);
        }
        ticks++;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof DeathScreen) {
            mc.player.respawn();
            mc.setScreen(null);
        } else if (mc.screen != null && ticks > 40) {
            mc.setScreen(null);
        }

        faceNearestPlayer(mc);
        relightFlashlight(mc);

        if (ticks % EVERY == 0) {
            String name = String.format("mp_%s_%03d.png", NAME, shots++);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
            HalloweenEventMod.LOGGER.info("[MP {}] {} | {}", NAME, name, state(mc));
        }
    }

    private static void faceNearestPlayer(Minecraft mc) {
        AbstractClientPlayer nearest = null;
        double best = 32 * 32;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            double d = p.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest == null) return;
        Vec3 to = nearest.getEyePosition().subtract(mc.player.getEyePosition());
        float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90f;
        // A little below their eyes, so the beam lands on them and the ground in between.
        float pitch = (float) (-Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * Mth.RAD_TO_DEG) + 12f;
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    private static void relightFlashlight(Minecraft mc) {
        InteractionHand hand = FlashlightItem.is(mc.player.getOffhandItem()) ? InteractionHand.OFF_HAND
                : FlashlightItem.is(mc.player.getMainHandItem()) ? InteractionHand.MAIN_HAND : null;
        if (hand == null || FlashlightItem.isOn(mc.player.getItemInHand(hand))) {
            offFor = 0;
            return;
        }
        if (++offFor < RELIGHT_AFTER) return;
        offFor = 0;
        HalloweenEventMod.LOGGER.info("[MP {}] right-click: switching the flashlight on", NAME);
        mc.gameMode.useItem(mc.player, hand);
    }

    private static String state(Minecraft mc) {
        ItemStack held = FlashlightItem.is(mc.player.getOffhandItem()) ? mc.player.getOffhandItem() : mc.player.getMainHandItem();
        int flashlights = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (FlashlightItem.is(mc.player.getInventory().getItem(i))) flashlights++;
        }
        return String.format("event %s, in zone %s, candy %d/%d, holding hands %s, flashlights %d, in hand %s%s, fear %d, "
                        + "own beam %.2f, fog %s %.2f, others %d, hp %.0f, pos %.1f %.1f %.1f",
                ClientState.active, ClientState.inZone, ClientState.count, ClientState.target, ClientState.holding,
                flashlights, FlashlightItem.is(held), FlashlightItem.isOn(held) ? " (on)" : FlashlightItem.is(held) ? " (off)" : "",
                FlashlightItem.is(held) ? FlashlightItem.fear(held) : 0, FlashlightRenderer.ownBeam(),
                ZoneFogRenderer.active(), ZoneFogRenderer.strength(), mc.level.players().size() - 1,
                mc.player.getHealth(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }
}
