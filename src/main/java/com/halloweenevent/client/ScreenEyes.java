package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.ScareKind;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.event.ViewportEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen-space eyes, a GUI layer (works with or without shader packs).
 * <ul>
 *   <li>Eye swarm / eye wall: almond eyes open, blink at random and close again; their irises stare at the
 *       middle of the screen (at you) with small darting glances.</li>
 *   <li>Hypnosis eye: one big red eye with a rotating spiral iris; the field of view breathes while it is open.</li>
 * </ul>
 * Each eye is three layers: sclera, a moving iris, then a lid mask whose almond hole opens over 4 frames. The lid
 * frame is drawn 1.25x the sclera so its hole matches the sclera exactly and hides the iris when it wanders.
 */
public final class ScreenEyes {
    private static final ResourceLocation SCLERA = tex("textures/misc/eye_sclera.png");
    private static final ResourceLocation IRIS = tex("textures/misc/eye_iris.png");
    private static final ResourceLocation LIDS = tex("textures/misc/eye_lids.png");
    private static final ResourceLocation SPIRAL = tex("textures/misc/eye_spiral.png");
    private static final int OPEN_TICKS = 6;
    private static final int MAX_EYES = 48;
    private static final RandomSource RNG = RandomSource.create();

    private record Eye(float fx, float fy, float size, int start, int end, int seed) {}

    private static final List<Eye> EYES = new ArrayList<>();
    private static int tick;
    private static int hypnoStart;
    private static int hypnoEnd;

    private ScreenEyes() {}

    private static ResourceLocation tex(String path) {
        return new ResourceLocation(HalloweenEventMod.MODID, path);
    }

    // ---- triggers ----

    /** A few eyes open around the edges of the screen, staggered. */
    public static void swarm(int count, int duration) {
        if (!ClientState.allows(ScareKind.EYES)) return;
        for (int i = 0; i < count && EYES.size() < MAX_EYES; i++) {
            float fx, fy;
            if (RNG.nextBoolean()) {
                fx = RNG.nextBoolean() ? RNG.nextFloat() * 0.22f : 0.78f + RNG.nextFloat() * 0.22f;
                fy = RNG.nextFloat();
            } else {
                fx = RNG.nextFloat();
                fy = RNG.nextBoolean() ? RNG.nextFloat() * 0.2f : 0.8f + RNG.nextFloat() * 0.2f;
            }
            int start = tick + RNG.nextInt(Math.max(1, duration / 3));
            EYES.add(new Eye(fx, fy, 0.09f + RNG.nextFloat() * 0.08f, start, start + duration, RNG.nextInt()));
        }
    }

    /** The whole screen fills with staring eyes on a jittered grid. */
    public static void wall(int duration) {
        if (!ClientState.allows(ScareKind.EYES)) return;
        int cols = 7, rows = 6;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols && EYES.size() < MAX_EYES; c++) {
            float fx = (c + 0.5f + (RNG.nextFloat() - 0.5f) * 0.5f) / cols;
            float fy = (r + 0.5f + (RNG.nextFloat() - 0.5f) * 0.5f) / rows;
            int start = tick + RNG.nextInt(6);
            EYES.add(new Eye(fx, fy, 0.08f + RNG.nextFloat() * 0.07f, start, start + duration, RNG.nextInt()));
        }
    }

    public static void hypnosis(int duration) {
        if (!ClientState.allows(ScareKind.HYPNO)) return;
        hypnoStart = tick;
        hypnoEnd = Math.max(hypnoEnd, tick + duration);
    }

    public static void clear() {
        EYES.clear();
        hypnoEnd = tick;
    }

    public static void clientTick() {
        tick++;
        if (!EYES.isEmpty()) EYES.removeIf(e -> tick > e.end());
    }

    private static float hash(int n) {
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    // ---- rendering ----

    public static void render(GuiGraphics g, float partialTick, int w, int h) {
        if (EYES.isEmpty() && tick >= hypnoEnd) return;
        float time = tick + partialTick;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (Eye e : EYES) {
            float age = time - e.start();
            if (age < 0) continue;
            float life = e.end() - e.start();
            int size = (int) (Math.min(w, h) * e.size() * 2f);
            int cx = (int) (e.fx() * w), cy = (int) (e.fy() * h);
            // Iris stares at the screen centre, with a darting glance now and then.
            float dx = w / 2f - cx, dy = h / 2f - cy;
            float len = Math.max(1f, Mth.sqrt(dx * dx + dy * dy));
            int glance = Mth.floor(time / 10f);
            float jx = hash(glance * 7 + e.seed()) > 0.7f ? (hash(glance * 3 + e.seed()) - 0.5f) * 0.5f : 0f;
            float ix = (dx / len + jx) * size * 0.12f;
            float iy = (dy / len) * size * 0.06f;
            drawEye(g, cx, cy, size, ix, iy, lidFrame(age, life, e.seed(), time), Math.min(1f, age / 3f), false, 0f);
        }
        if (tick < hypnoEnd) {
            float age = time - hypnoStart, life = hypnoEnd - hypnoStart;
            int size = (int) (Math.min(w, h) * (0.55f + 0.05f * Mth.sin(time * 0.3f)));
            drawEye(g, w / 2, h / 2, size, 0f, 0f, lidFrame(age, life, 0, time), Math.min(1f, age / 4f), true, time * 9f);
        }
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** 0 closed .. 3 open: opens over 6 ticks, closes over the last 6, blinks at random in between. */
    private static int lidFrame(float age, float life, int seed, float time) {
        float open = Math.min(age, life - age) / OPEN_TICKS;
        if (open <= 0f) return 0;
        if (open < 1f) return Mth.clamp((int) (open * 3f) + 1, 1, 3);
        int window = Mth.floor(time / 40f);
        float into = time - window * 40f;
        if (hash(window * 11 + seed) > 0.75f && into < 3f) return into < 1.5f ? 1 : 0;
        return 3;
    }

    private static void drawEye(GuiGraphics g, int cx, int cy, int size, float ix, float iy, int lid, float alpha,
                                boolean hypno, float spin) {
        int sw = size, sh = size / 2;
        if (hypno) g.setColor(1f, 0.45f, 0.45f, alpha);
        else g.setColor(1f, 1f, 1f, alpha);
        g.blit(SCLERA, cx - sw / 2, cy - sh / 2, sw, sh, 0, 0, 256, 128, 256, 128);

        g.setColor(1f, 1f, 1f, alpha);
        if (hypno) {
            int r = (int) (sh * 0.95f);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(spin));
            g.blit(SPIRAL, -r / 2, -r / 2, r, r, 0, 0, 256, 256, 256, 256);
            g.pose().popPose();
        } else {
            int d = (int) (sh * 0.84f);
            g.blit(IRIS, (int) (cx - d / 2f + ix), (int) (cy - d / 2f + iy), d, d, 0, 0, 128, 128, 128, 128);
        }

        int lw = (int) (sw * 1.25f), lh = (int) (sh * 1.25f);
        g.blit(LIDS, cx - lw / 2, cy - lh / 2, lw, lh, 0, lid * 128, 256, 128, 256, 512);
    }

    /** The hypnosis eye makes the world breathe in and out. */
    public static void fov(ViewportEvent.ComputeFov event) {
        if (tick >= hypnoEnd) return;
        float t = tick + (float) event.getPartialTick();
        float fade = Mth.clamp(Math.min(t - hypnoStart, hypnoEnd - t) / 10f, 0f, 1f);
        event.setFOV(event.getFOV() * (1.0 + 0.09 * fade * Mth.sin(t * 0.25f)));
    }
}
