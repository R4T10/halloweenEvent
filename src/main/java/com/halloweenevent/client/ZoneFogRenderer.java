package com.halloweenevent.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.halloweenevent.item.FlashlightItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Zone fog: always on inside an active zone, thinner while holding a hand, thicker during scares.
 * A full-screen pass over the scene colour and depth (core shader {@code halloweenevent:zone_fog}, run through
 * {@link FullscreenPass}).
 * <p>
 * Depth and matrices are captured at {@code AFTER_LEVEL}. Oculus renders into the same main depth texture
 * (its depthtex0), so the capture is valid with or without a shader pack. Drawing happens right away without
 * a shader pack, or after Oculus has composited the frame ({@link #renderAfterShaderPack()}), because anything
 * drawn earlier is overwritten by the pack's composite passes.
 */
public final class ZoneFogRenderer {
    private static final float DRIFT = 0.025f;
    /** Fog thickness alone, by scare level 1-3 (holding a hand: a quarter less). */
    private static final float[] THICKNESS = {0f, 0.62f, 0.74f, 0.86f};

    private static int fear = 2;

    static ShaderInstance shader;

    private static TextureTarget depthCopy;
    private static int depthTexture = -1;
    /** 0 outside the zone, fades to 1 inside. */
    private static float zoneBlend;
    private static float prevZoneBlend;
    /** 0 alone, fades to 1 while holding a hand. */
    private static float holdBlend;
    private static float prevHoldBlend;

    private static boolean pending;
    private static final Matrix4f INV_VIEW_PROJ = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static float frameZone;
    private static float frameHold;
    /** The world's own fog colour this frame (time of day, biome, weather). */
    private static final float[] WORLD_FOG = new float[3];
    private static float daylight = 1f;

    private ZoneFogRenderer() {}

    public static void clientTick() {
        prevZoneBlend = zoneBlend;
        prevHoldBlend = holdBlend;
        zoneBlend += ((ClientState.active && ClientState.inZone ? 1f : 0f) - zoneBlend) * 0.05f;
        holdBlend += ((ClientState.holding ? 1f : 0f) - holdBlend) * 0.04f;
        fear = ownFear();
    }

    /** The player's scare level, read from the event flashlight the server keeps current ({@code Fear}); 2 without one. */
    private static int ownFear() {
        Player p = Minecraft.getInstance().player;
        if (p == null) return 2;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (FlashlightItem.is(stack)) return FlashlightItem.fear(stack);
        }
        return 2;
    }

    public static void reset() {
        zoneBlend = prevZoneBlend = holdBlend = prevHoldBlend = 0f;
        pending = false;
    }

    /** Automated test only: vanilla frames without the zone fog. */
    static boolean testDisabled;

    /** True while the depth fog is drawn; vanilla fog and the mist overlay stand aside. */
    public static boolean active() {
        return shader != null && zoneBlend > 0.01f && !testDisabled;
    }

    /** Current fog strength 0..1 for the screen mist and vignette. */
    public static float strength() {
        return zoneBlend * Mth.lerp(holdBlend, 1f, 0.6f);
    }

    /** 0.45..1: the screen mist is dimmed at night like the 3D fog. */
    public static float brightness() {
        return Mth.clamp(daylight, 0.45f, 1f);
    }

    public static void onLevelRendered(RenderLevelStageEvent event) {
        pending = false;
        Minecraft mc = Minecraft.getInstance();
        if (shader == null || mc.level == null) return;
        float pt = event.getPartialTick();
        frameZone = Mth.lerp(pt, prevZoneBlend, zoneBlend);
        frameHold = Mth.lerp(pt, prevHoldBlend, holdBlend);
        if (frameZone < 0.01f || testDisabled) return;

        camera = event.getCamera().getPosition();
        float[] worldFog = RenderSystem.getShaderFogColor();
        System.arraycopy(worldFog, 0, WORLD_FOG, 0, 3);
        daylight = mc.level.getSkyDarken(pt);
        INV_VIEW_PROJ.set(event.getProjectionMatrix()).mul(FlashlightRenderer.viewRotation(event.getCamera())).invert();
        pending = true;
        if (!ShaderCompat.shaderPackInUse()) {
            // Drawn right now: sample the main depth texture itself. A blitted copy read as all sky here
            // (fog over the whole screen), the same failure the flashlight pass had.
            depthTexture = mc.getMainRenderTarget().getDepthTextureId();
            mc.getMainRenderTarget().bindWrite(false);
            draw();
        } else {
            // Drawn later, after the hand cleared the depth: keep a copy of this frame's world depth.
            copyDepth(mc.getMainRenderTarget());
            depthTexture = depthCopy.getDepthTextureId();
        }
    }

    /** Shader pack active: Oculus has finished the frame; draw onto its final image. */
    public static void renderAfterShaderPack() {
        if (pending && ShaderCompat.shaderPackInUse()) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            draw();
        }
    }

    /** Copies the main depth texture, then restores whatever framebuffers (vanilla or Oculus) were bound. */
    private static void copyDepth(RenderTarget main) {
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int write = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (depthCopy == null) {
            depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        } else if (depthCopy.width != main.width || depthCopy.height != main.height) {
            depthCopy.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        depthCopy.copyDepthFrom(main);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, write);
    }

    private static void draw() {
        pending = false;
        // The original look: the same thickness over the whole screen, near or far (the first version read every
        // pixel as sky through a depth bug, and that is the look the event wants). Thicker the higher the player's
        // scare level, a quarter thinner holding a hand; scare stages add more and fade back on their own.
        // start/end only size the wisps.
        float scare = ScareEffects.fogLevel();
        float start = Mth.lerp(frameHold, 4.0f, 8.0f) - scare;
        float end = Mth.lerp(frameHold, 30f, 40f) - scare * 6f;
        float alone = THICKNESS[fear];
        float max = Math.min(0.95f, Mth.lerp(frameHold, alone, alone * 0.75f) + scare * 0.12f) * frameZone;
        float red = ScareEffects.fogRed() ? Math.min(1f, scare) : 0f;

        // Real mist, not darkness: the world's fog colour pulled towards pale grey, dimmed at night but never black.
        float light = Mth.clamp(daylight, 0.3f, 1f);
        float fr = Mth.lerp(0.6f, WORLD_FOG[0], 0.70f) * light;
        float fg = Mth.lerp(0.6f, WORLD_FOG[1], 0.72f) * light;
        float fb = Mth.lerp(0.6f, WORLD_FOG[2], 0.76f) * light;

        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        shader.setSampler("DiffuseSampler", main.getColorTextureId());
        shader.setSampler("DepthSampler", depthTexture);
        shader.safeGetUniform("InvViewProj").set(INV_VIEW_PROJ);
        shader.safeGetUniform("Fog").set(Math.max(0f, start), Math.max(start + 1f, end), max, DRIFT);
        // Your own lit flashlight cuts the fog out of its cone (pulsing with the flicker); the rest stays foggy.
        float beam = FlashlightRenderer.ownBeam();
        Vec3 bo = FlashlightRenderer.ownOrigin, bd = FlashlightRenderer.ownDir;
        shader.safeGetUniform("BeamPos").set((float) bo.x, (float) bo.y, (float) bo.z, beam);
        shader.safeGetUniform("BeamCone").set((float) bd.x, (float) bd.y, (float) bd.z, FlashlightRenderer.COS_OUTER);
        shader.safeGetUniform("BeamRange").set(FlashlightRenderer.RANGE);
        shader.safeGetUniform("CamMod").set((float) (camera.x % 4096.0), (float) (camera.y % 4096.0), (float) (camera.z % 4096.0));
        shader.safeGetUniform("ZoneFogColor").set(Mth.lerp(red, fr, 0.35f), Mth.lerp(red, fg, 0.05f), Mth.lerp(red, fb, 0.05f));

        if (FlashlightRenderer.DEBUG_LOG) probe(start, end, max);
        // Mixes the fog into the scene colour itself (no GL blending) and never samples the framebuffer it draws
        // into: both caused full-screen grey or hard-edged holes before.
        FullscreenPass.draw(shader, main);
    }

    private static long lastProbe;

    /** Every 5 s: the fog's inputs at the screen centre (depth, rebuilt distance, GL state), to debug full-screen fog. */
    private static void probe(float start, float end, float max) {
        long now = System.currentTimeMillis();
        if (now - lastProbe < 5000) return;
        lastProbe = now;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        int drawFbo = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        java.nio.FloatBuffer d = org.lwjgl.BufferUtils.createFloatBuffer(1);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL11.glReadPixels(main.width / 2, main.height / 2, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, d);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        org.joml.Vector4f p = new org.joml.Vector4f(0, 0, d.get(0) * 2 - 1, 1).mul(INV_VIEW_PROJ);
        p.div(p.w);
        com.halloweenevent.HalloweenEventMod.LOGGER.info(
                "[ZoneFog] centre depth {} -> distance {}, fog {}..{} max {}, depth tex {} (main {}), draw fbo {} (main {}), viewport {},{} {}x{} (target {}x{}), flashlight on {}",
                d.get(0), String.format("%.2f", Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z)), start, end, max,
                depthTexture, main.getDepthTextureId(), drawFbo, main.frameBufferId, vp[0], vp[1], vp[2], vp[3],
                main.width, main.height, FlashlightRenderer.ownBeam() > 0);
    }
}
