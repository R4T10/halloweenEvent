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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

/**
 * Zone fog: always on inside an active zone, thinner while holding a hand, thicker during scares.
 * A full-screen pass over a copy of the depth buffer (core shader {@code halloweenevent:zone_fog}).
 * <p>
 * Depth and matrices are captured at {@code AFTER_LEVEL}. Oculus renders into the same main depth texture
 * (its depthtex0), so the capture is valid with or without a shader pack. Drawing happens right away without
 * a shader pack, or after Oculus has composited the frame ({@link #renderAfterShaderPack()}), because anything
 * drawn earlier is overwritten by the pack's composite passes.
 */
public final class ZoneFogRenderer {
    private static final float DRIFT = 0.025f;

    static ShaderInstance shader;

    private static TextureTarget depthCopy;
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
    }

    public static void reset() {
        zoneBlend = prevZoneBlend = holdBlend = prevHoldBlend = 0f;
        pending = false;
    }

    /** True while the depth fog is drawn; vanilla fog and the mist overlay stand aside. */
    public static boolean active() {
        return shader != null && zoneBlend > 0.01f;
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
        if (frameZone < 0.01f) return;

        camera = event.getCamera().getPosition();
        float[] worldFog = RenderSystem.getShaderFogColor();
        System.arraycopy(worldFog, 0, WORLD_FOG, 0, 3);
        daylight = mc.level.getSkyDarken(pt);
        INV_VIEW_PROJ.set(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose()).invert();
        copyDepth(mc.getMainRenderTarget());
        pending = true;
        if (!ShaderCompat.shaderPackInUse()) {
            mc.getMainRenderTarget().bindWrite(false);
            draw();
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
        // Alone: from 4 blocks, thickest by 30 (max 55%). Holding a hand: from 8, by 40, max 35%.
        // The path stays readable; scares thicken it a little.
        float scare = ScareEffects.fogLevel();
        float start = Mth.lerp(frameHold, 4.0f, 8.0f) - scare;
        float end = Mth.lerp(frameHold, 30f, 40f) - scare * 6f;
        float max = Math.min(0.7f, Mth.lerp(frameHold, 0.55f, 0.35f) + scare * 0.1f) * frameZone;
        float red = ScareEffects.fogRed() ? Math.min(1f, scare) : 0f;

        // Real mist, not darkness: the world's fog colour pulled towards pale grey, dimmed at night but never black.
        float light = Mth.clamp(daylight, 0.3f, 1f);
        float fr = Mth.lerp(0.6f, WORLD_FOG[0], 0.70f) * light;
        float fg = Mth.lerp(0.6f, WORLD_FOG[1], 0.72f) * light;
        float fb = Mth.lerp(0.6f, WORLD_FOG[2], 0.76f) * light;

        shader.setSampler("DepthSampler", depthCopy.getDepthTextureId());
        shader.safeGetUniform("InvViewProj").set(INV_VIEW_PROJ);
        shader.safeGetUniform("Fog").set(Math.max(0f, start), Math.max(start + 1f, end), max, DRIFT);
        shader.safeGetUniform("CamMod").set((float) (camera.x % 4096.0), (float) (camera.y % 4096.0), (float) (camera.z % 4096.0));
        shader.safeGetUniform("ZoneFogColor").set(Mth.lerp(red, fr, 0.35f), Mth.lerp(red, fg, 0.05f), Mth.lerp(red, fb, 0.05f));

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(() -> shader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.vertex(-1, -1, 0).endVertex();
        b.vertex(1, -1, 0).endVertex();
        b.vertex(1, 1, 0).endVertex();
        b.vertex(-1, 1, 0).endVertex();
        BufferUploader.drawWithShader(b.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
