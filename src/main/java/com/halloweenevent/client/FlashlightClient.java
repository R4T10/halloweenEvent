package com.halloweenevent.client;

import com.halloweenevent.item.FlashlightItem;
import com.halloweenevent.registry.ModSounds;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the flashlight: how bright each lit flashlight is right now (struggle, event flicker and
 * brown-outs, all stronger at higher scare levels) and the struggle's clicks. Everything is derived from the
 * stack NBT and the game time, so every client sees and hears roughly the same thing without extra packets.
 */
public final class FlashlightClient {
    /** Development only: cycles the beam pass's debug views (block grid, light amount, distance). */
    public static final KeyMapping DEBUG_KEY = new KeyMapping("key.halloweenevent.flashlight_debug",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.halloweenevent");
    public static final boolean DEBUG_KEY_ENABLED = !FMLEnvironment.production;

    /**
     * Struggling switch-on, one character per tick: 0 off, 1-9 dim, F full. Clicks play on every off-to-on edge;
     * the last edge is the light finally catching. Both patterns are {@link FlashlightItem#STRUGGLE_TICKS} long.
     */
    private static final String STRUGGLE = "000000FF000000400000000FFF000F0F000006000000F";
    /** A struggle that gives up (scare level 2+): it nearly catches, then dies; the server switches it off. */
    private static final String GIVE_UP = "000000FF000000400000000FFF000F0FFFF6300F00000";
    private static final double SOUND_RANGE_SQ = 24 * 24;

    /** Indexed by scare level 1-3. */
    private static final float[] FLICKER_SCALE = {0, 0.6f, 1.0f, 1.7f};
    private static final float[] BROWNOUT_CHANCE = {0, 0f, 0.22f, 0.4f};
    private static final float[] BROWNOUT_DEPTH = {0, 0f, 0.55f, 0.8f};

    /** Per player entity id: which struggle we last voiced and how far. */
    private record Voiced(long startAt, int age) {}

    private static final Int2ObjectOpenHashMap<Voiced> VOICED = new Int2ObjectOpenHashMap<>();

    /** No event flicker (automated test screenshots). */
    static boolean steady;

    private FlashlightClient() {}

    public static void clientTick(Minecraft mc) {
        while (DEBUG_KEY_ENABLED && DEBUG_KEY.consumeClick()) {
            if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("Flashlight debug: " + FlashlightRenderer.cycleDebugView()), true);
        }
        voiceStruggles(mc);
    }

    public static void reset() {
        VOICED.clear();
    }

    /** 0..1 brightness of a player's lit flashlight this frame. */
    public static float intensity(Player player, ItemStack stack, long gameTime, float partialTick) {
        long startAt = FlashlightItem.startAt(stack);
        if (startAt != Long.MIN_VALUE) {
            long age = gameTime - startAt;
            // Negative: this client's clock is a little behind the server's; the struggle has not begun here yet.
            if (age < 0) return 0f;
            String pattern = pattern(stack);
            if (age < pattern.length()) return level(pattern, (int) age);
            // A struggle that gave up: dark until the server's switch-off arrives.
            if (FlashlightItem.failing(stack)) return 0f;
        }
        if (steady || !(ClientState.active && ClientState.inZone)) return 1f;
        return eventFlicker(player.getId(), gameTime + partialTick, FlashlightItem.fear(stack));
    }

    private static String pattern(ItemStack stack) {
        return FlashlightItem.failing(stack) ? GIVE_UP : STRUGGLE;
    }

    private static float level(String pattern, int age) {
        char c = pattern.charAt(age);
        return c == 'F' ? 1f : (c - '0') / 9f;
    }

    /**
     * Inside the zone: a faint constant wobble, bursts of hard flicker in some 1.5 s windows, and from scare level 2
     * slow brown-outs where the light sags for a few seconds. All more likely alone and during scares.
     */
    private static float eventFlicker(int seed, float t, int fear) {
        long tick = (long) Math.floor(t);
        float wobble = 0.9f + 0.1f * Mth.sin(t * 0.37f + seed) * Mth.sin(t * 0.13f + seed * 0.7f);

        long slow = tick / 80;
        if (hash(seed, slow, 5) < BROWNOUT_CHANCE[fear]) {
            float env = Mth.sin((float) Math.PI * ((t - slow * 80) / 80f));
            wobble *= 1f - BROWNOUT_DEPTH[fear] * env * env;
        }

        long window = tick / 30;
        float chance = (ClientState.holding ? 0.06f : 0.14f) * FLICKER_SCALE[fear] + ScareEffects.fogLevel() * 0.35f;
        if (hash(seed, window, 0) >= chance) return wobble;
        int start = (int) (hash(seed, window, 1) * 16);
        int length = 3 + fear * 2 + (int) (hash(seed, window, 2) * 6);
        int at = (int) (tick % 30);
        if (at < start || at >= start + length) return wobble;
        float h = hash(seed, tick, 3);
        return h < 0.45f ? 0f : h < 0.7f ? 0.3f * wobble : wobble;
    }

    private static float hash(int seed, long a, int salt) {
        long x = seed * 0x9E3779B97F4A7C15L + a * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return (x >>> 40) / (float) (1L << 24);
    }

    /** Plays the strain whine, each stutter click and the final catch for struggles near the camera. */
    private static void voiceStruggles(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        long now = mc.level.getGameTime();
        for (AbstractClientPlayer p : mc.level.players()) {
            ItemStack stack = FlashlightItem.heldLit(p);
            long startAt = stack.isEmpty() ? Long.MIN_VALUE : FlashlightItem.startAt(stack);
            if (startAt == Long.MIN_VALUE || p.distanceToSqr(mc.player) > SOUND_RANGE_SQ) {
                VOICED.remove(p.getId());
                continue;
            }
            String pattern = pattern(stack);
            int age = (int) Mth.clamp(now - startAt, -1, pattern.length());
            Voiced v = VOICED.get(p.getId());
            int from = v != null && v.startAt() == startAt ? v.age() + 1 : 0;
            // Joining late (or a long lag spike): voice only the latest tick instead of a burst of clicks.
            if (age - from > 4) from = age;
            for (int a = from; a <= age && a < pattern.length(); a++) {
                if (a == 0) play(mc, p, ModSounds.FLASHLIGHT_STRAIN.get(), 1f);
                boolean lit = level(pattern, a) > 0f;
                boolean wasLit = a > 0 && level(pattern, a - 1) > 0f;
                if (lit && !wasLit) {
                    boolean catches = pattern.indexOf('0', a) < 0;
                    if (catches) play(mc, p, ModSounds.FLASHLIGHT_CLICK.get(), 1f);
                    else play(mc, p, ModSounds.FLASHLIGHT_FLICKER.get(), 0.85f + hash(p.getId(), a, 4) * 0.4f);
                }
            }
            VOICED.put(p.getId(), new Voiced(startAt, age));
        }
    }

    private static void play(Minecraft mc, Player p, SoundEvent sound, float pitch) {
        mc.level.playLocalSound(p.getX(), p.getEyeY(), p.getZ(), sound, SoundSource.PLAYERS, 0.7f, pitch, false);
    }
}
