package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.item.FlashlightItem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flashlight beams: a full-screen pass (core shader {@code halloweenevent:flashlight}) that rebuilds each pixel's
 * position from a depth copy and lights it from up to {@value #MAX_LIGHTS} spot lights.
 * <p>
 * A lit pixel gets light proportional to its surface colour (albedo), not its current shaded colour: at night the
 * shaded colour is nearly black and brightening it does nothing. The albedo is estimated by dividing by the
 * ambient light level at the camera ({@code Ambient}). No light scattered in the air: its edge showed as hard
 * lines fanning out from the hand.
 * <p>
 * Same timing as {@link ZoneFogRenderer}: matrices at {@code AFTER_LEVEL}; drawn right away without a shader pack
 * (reading the main depth texture directly), otherwise after Oculus has composited the frame (reading a depth copy
 * taken at {@code AFTER_LEVEL}). Drawn before the fog: lighting fog-covered pixels only made a grey blob, so the
 * surfaces are lit first and the zone fog leaves the camera player's own beam clear ({@link #ownBeam()}).
 * The pass runs through {@link FullscreenPass}: it reads the main colour and depth as textures, draws elsewhere
 * and copies the lit result back.
 */
public final class FlashlightRenderer {
    /** Logs what the renderer does (state changes, plus a summary every {@value #LOG_INTERVAL_MS} ms while lit); development runs only. */
    static final boolean DEBUG_LOG = !net.minecraftforge.fml.loading.FMLEnvironment.production;
    private static final long LOG_INTERVAL_MS = 5000;

    private static final int MAX_LIGHTS = 4;
    /** A weak, dying torch on purpose: you see where you step, not what waits further out. */
    static final float RANGE = 13f;
    /** Bright core, soft main cone, then a faint wide spill so the edge never feels like a tunnel. */
    private static final float COS_INNER = (float) Math.cos(Math.toRadians(12));
    static final float COS_OUTER = (float) Math.cos(Math.toRadians(24));
    private static final float COS_SPILL = (float) Math.cos(Math.toRadians(38));
    /** Light added at the brightest spot, times the surface colour; flat lift for black surfaces. */
    private static final float GAIN = 0.6f;
    private static final float LIFT = 0.02f;
    private static final float[] COLOR = {1.0f, 0.88f, 0.68f};
    /** Other players' beams beyond this are lost in the fog anyway; keeps crowds (200 players) cheap. */
    private static final double VISIBLE_DISTANCE_SQ = 32 * 32;

    static ShaderInstance shader;

    private static TextureTarget copy;
    private static boolean pending;
    private static int lightCount;
    private static final FloatBuffer POS = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private static final FloatBuffer DIR = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private static final Matrix4f INV_VIEW_PROJ = new Matrix4f();
    private static float ambient = 1f;
    private static float ownBeam;
    private static Vec3 camPos = Vec3.ZERO;
    /** Debug view (flashlight debug key): 0 off, 1 block grid, 2 light amount, 3 distance. */
    private static int debugView;
    private static final String[] DEBUG_NAMES = {"off", "block grid (lines must sit exactly on block edges)",
            "light amount (white = lit, red tint = inside the cone)", "distance (black = near, white = beam range)"};
    /** The camera player's own beam this frame, camera-relative; valid while {@link #ownBeam} > 0. */
    static Vec3 ownOrigin = Vec3.ZERO;
    static Vec3 ownDir = new Vec3(0, 0, 1);
    /** Depth the pass reads this frame (main target's own texture, or the copy under a shader pack). */
    private static int depthTexture = -1;
    private static int depthFbo;

    private static ShaderInstance locationsFor;
    private static int posLocation = -1;
    private static int dirLocation = -1;

    // Debug log state.
    private static String lastState = "";
    private static long lastLog;
    private static int draws;
    private static List<Light> lastLights = List.of();

    private record Light(double distSq, boolean own, String name, Vec3 origin, Vec3 dir, float intensity) {}

    private FlashlightRenderer() {}

    public static void reset() {
        pending = false;
        ownBeam = 0f;
        lastState = "";
    }

    /** Camera roll this frame, recorded from {@code ViewportEvent.ComputeCameraAngles} (the camera does not keep it). */
    static float cameraRoll;

    /**
     * The camera rotation the level was drawn with: roll, pitch, then yaw + 180, as GameRenderer builds it.
     * The event's PoseStack at {@code AFTER_LEVEL} is not this matrix (rebuilt positions all collapsed onto the
     * camera), so it is rebuilt here. The projection matrix from the event already contains view bobbing.
     */
    static Matrix4f viewRotation(Camera camera) {
        return new Matrix4f()
                .rotateZ((float) Math.toRadians(cameraRoll))
                .rotateX((float) Math.toRadians(camera.getXRot()))
                .rotateY((float) Math.toRadians(camera.getYRot() + 180f));
    }

    static void setDebugView(int view) {
        debugView = view;
    }

    /** The next drawn frame logs its summary and centre probe right away. */
    static void requestSummary() {
        lastLog = 0;
    }

    /** Cycles the debug view; returns its description for the action bar. */
    public static String cycleDebugView() {
        debugView = (debugView + 1) % DEBUG_NAMES.length;
        log("debug view: " + DEBUG_NAMES[debugView]);
        return DEBUG_NAMES[debugView];
    }

    /** 0..1: how bright the camera player's own flashlight is this frame (flicker included); clears the zone fog in its cone. */
    public static float ownBeam() {
        return ownBeam;
    }

    /** Called from the shader registration; logs whether the beam shader is usable at all. */
    static void onShaderLoaded(ShaderInstance s) {
        shader = s;
        locationsFor = null;
        log("shader loaded: program id " + s.getId());
    }

    public static void onLevelRendered(RenderLevelStageEvent event) {
        pending = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (shader == null) {
            if (mc.player != null && !FlashlightItem.heldLit(mc.player).isEmpty()) state("no shader: flashlight is on but the beam shader failed to load (see the error above)");
            return;
        }
        Camera camera = event.getCamera();
        ownBeam = 0f;
        if (!collectLights(mc, camera, event.getPartialTick())) {
            state("idle: no lit flashlight in anyone's hands");
            return;
        }

        ambient = ambientAt(mc, camera, event.getPartialTick());
        INV_VIEW_PROJ.set(event.getProjectionMatrix()).mul(viewRotation(camera)).invert();
        camPos = camera.getPosition();
        RenderTarget main = mc.getMainRenderTarget();
        ensureCopy(main);
        boolean pack = ShaderCompat.shaderPackInUse();
        if (pack) {
            // Drawn later, after the hand cleared the depth: keep a copy of this frame's world depth.
            blit(main, GL30.GL_DEPTH_BUFFER_BIT);
            depthTexture = copy.getDepthTextureId();
            depthFbo = copy.frameBufferId;
        } else {
            // Drawn right now: sample the main depth texture itself (FullscreenPass never draws into its framebuffer).
            depthTexture = main.getDepthTextureId();
            depthFbo = main.frameBufferId;
        }
        pending = true;
        state("lighting: " + lightCount + " beam(s), " + (pack ? "shader pack (drawn after Oculus composite, depth copy)"
                : "vanilla (drawn at AFTER_LEVEL, main depth texture " + depthTexture + ")"));
        if (!pack) draw(main);
    }

    /** Shader pack active: Oculus has finished the frame; light its final image. */
    public static void renderAfterShaderPack() {
        if (pending && ShaderCompat.shaderPackInUse()) draw(Minecraft.getInstance().getMainRenderTarget());
    }

    /**
     * Rough brightness of the world around the camera, 0.03..1, used to recover surface colour from the shaded
     * pixel. Follows the vanilla light curve loosely: block light, or sky light dimmed by night.
     */
    private static float ambientAt(Minecraft mc, Camera camera, float pt) {
        BlockPos pos = BlockPos.containing(camera.getPosition());
        float block = mc.level.getBrightness(LightLayer.BLOCK, pos) / 15f;
        float sky = mc.level.getBrightness(LightLayer.SKY, pos) / 15f * mc.level.getSkyDarken(pt);
        float level = Math.max(block, sky);
        float gamma = mc.options.gamma().get().floatValue();
        return Mth.clamp(0.06f + 0.06f * gamma + 0.9f * level * level, 0.03f, 1f);
    }

    /** Fills POS/DIR (camera-relative) with the nearest lit flashlights, the camera's own first. */
    private static boolean collectLights(Minecraft mc, Camera camera, float pt) {
        Vec3 cam = camera.getPosition();
        long time = mc.level.getGameTime();
        List<Light> lights = new ArrayList<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.isSpectator()) continue;
            ItemStack stack = FlashlightItem.heldLit(p);
            if (stack.isEmpty()) continue;
            double distSq = p.distanceToSqr(cam);
            if (distSq > VISIBLE_DISTANCE_SQ) continue;
            float intensity = FlashlightClient.intensity(p, stack, time, pt);
            // Flickered fully off this frame: keep the slot so the log still shows the light, but it adds nothing.

            boolean own = p == camera.getEntity();
            boolean firstPerson = own && !camera.isDetached();
            Vec3 dir = firstPerson ? new Vec3(camera.getLookVector()) : p.getViewVector(pt);
            Vec3 eye = firstPerson ? cam : p.getEyePosition(pt);
            Vec3 right = dir.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : right.normalize();
            boolean mainHand = p.getMainHandItem() == stack;
            boolean rightSide = (p.getMainArm() == HumanoidArm.RIGHT) == mainHand;
            double side = rightSide ? 1 : -1;
            // Roughly where the hand holds it: beside and below the eyes, a little forward.
            Vec3 origin = firstPerson
                    ? eye.add(right.scale(0.22 * side)).add(0, -0.18, 0).add(dir.scale(0.2))
                    : eye.add(right.scale(0.35 * side)).add(0, -0.4, 0).add(dir.scale(0.35));
            lights.add(new Light(distSq, own, p.getGameProfile().getName(), origin.subtract(cam), dir, intensity));
        }
        ownBeam = 0f;
        for (Light l : lights) {
            if (!l.own()) continue;
            ownBeam = l.intensity();
            ownOrigin = l.origin();
            ownDir = l.dir();
        }
        if (lights.isEmpty()) return false;
        lights.sort(Comparator.comparing((Light l) -> !l.own()).thenComparingDouble(Light::distSq));

        POS.clear();
        DIR.clear();
        lightCount = Math.min(MAX_LIGHTS, lights.size());
        for (int i = 0; i < MAX_LIGHTS; i++) {
            Light l = i < lightCount ? lights.get(i) : null;
            POS.put(l == null ? 0 : (float) l.origin().x).put(l == null ? 0 : (float) l.origin().y)
                    .put(l == null ? 0 : (float) l.origin().z).put(l == null ? 0 : l.intensity());
            DIR.put(l == null ? 0 : (float) l.dir().x).put(l == null ? 0 : (float) l.dir().y)
                    .put(l == null ? 1 : (float) l.dir().z).put(0f);
        }
        POS.flip();
        DIR.flip();
        lastLights = lights.subList(0, lightCount);
        return true;
    }

    private static void ensureCopy(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            log("created scene copy " + main.width + "x" + main.height);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height, Minecraft.ON_OSX);
        }
    }

    /** Blits colour or depth from the main target into the copy, then restores the bound framebuffers. */
    private static void blit(RenderTarget main, int mask) {
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int write = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        clearGlErrors();
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, copy.width, copy.height, mask, GL30.GL_NEAREST);
        if (DEBUG_LOG) {
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) state("GL error 0x" + Integer.toHexString(err) + " copying "
                    + (mask == GL30.GL_DEPTH_BUFFER_BIT ? "depth (depth formats differ? every pixel then reads as sky)" : "colour"));
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, write);
    }

    private static void draw(RenderTarget main) {
        pending = false;
        if (copy == null || copy.width != main.width || copy.height != main.height) return;
        clearGlErrors();
        boolean summary = summaryDue();
        if (summary) readCentre(main.frameBufferId, main, GL11.GL_RGBA, CENTRE_BEFORE);

        // Read straight from the main target: FullscreenPass draws elsewhere and copies the result back.
        shader.setSampler("DiffuseSampler", main.getColorTextureId());
        shader.setSampler("DepthSampler", depthTexture);
        shader.safeGetUniform("InvViewProj").set(INV_VIEW_PROJ);
        shader.safeGetUniform("Beam").set(RANGE, COS_INNER, COS_OUTER, 0f);
        // Level 3 scares (red fog): the beam turns a sick red with them.
        float red = ScareEffects.fogRed() ? Math.min(1f, ScareEffects.fogLevel()) : 0f;
        shader.safeGetUniform("BeamColor").set(COLOR[0], Mth.lerp(red, COLOR[1], 0.35f), Mth.lerp(red, COLOR[2], 0.28f));
        shader.safeGetUniform("Strength").set(GAIN, LIFT, 0f);
        shader.safeGetUniform("LightCount").set((float) lightCount);
        shader.safeGetUniform("Ambient").set(ambient);
        shader.safeGetUniform("Spill").set(COS_SPILL);
        shader.safeGetUniform("DebugView").set((float) debugView);
        shader.safeGetUniform("CamMod").set((float) (camPos.x % 4096.0), (float) (camPos.y % 4096.0), (float) (camPos.z % 4096.0));

        // The light arrays are not vanilla uniforms; GL keeps them in the program object across apply().
        if (locationsFor != shader) {
            locationsFor = shader;
            posLocation = arrayLocation(shader.getId(), "LightPos");
            dirLocation = arrayLocation(shader.getId(), "LightDir");
            log("uniform locations: LightPos=" + posLocation + " LightDir=" + dirLocation
                    + (posLocation < 0 || dirLocation < 0 ? "  <-- MISSING: beams cannot be positioned, nothing will light up" : ""));
        }
        ProgramManager.glUseProgram(shader.getId());
        if (posLocation >= 0) GL20.glUniform4fv(posLocation, POS);
        if (dirLocation >= 0) GL20.glUniform4fv(dirLocation, DIR);
        FullscreenPass.draw(shader, main);

        draws++;
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) log("GL error 0x" + Integer.toHexString(err) + " while drawing the beam pass");
        if (summary) logSummary(main);
    }

    /** Some drivers only answer to the first element's name. */
    private static int arrayLocation(int program, String name) {
        int loc = GL20.glGetUniformLocation(program, name);
        return loc >= 0 ? loc : GL20.glGetUniformLocation(program, name + "[0]");
    }

    private static void clearGlErrors() {
        if (!DEBUG_LOG) return;
        for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // Errors from earlier rendering are not ours; drop them so the check after drawing is accurate.
        }
    }

    private static final FloatBuffer CENTRE_BEFORE = BufferUtils.createFloatBuffer(4);

    private static boolean summaryDue() {
        return DEBUG_LOG && System.currentTimeMillis() - lastLog >= LOG_INTERVAL_MS;
    }

    /** One pixel at the screen centre of {@code fbo}, restoring the read binding. */
    private static void readCentre(int fbo, RenderTarget main, int format, FloatBuffer into) {
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        into.clear();
        GL11.glReadPixels(main.width / 2, main.height / 2, 1, 1, format, GL11.GL_FLOAT, into);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
    }

    private static void logSummary(RenderTarget main) {
        lastLog = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("beam pass drawn %d time(s) in the last %ds, target %dx%d, ambient %.2f",
                draws, LOG_INTERVAL_MS / 1000, main.width, main.height, ambient));
        for (Light l : lastLights) {
            sb.append(String.format("%n    %s%s: intensity %.2f, origin (%.2f, %.2f, %.2f) from camera, dir (%.2f, %.2f, %.2f)",
                    l.name(), l.own() ? " (you)" : "", l.intensity(),
                    l.origin().x, l.origin().y, l.origin().z, l.dir().x, l.dir().y, l.dir().z));
        }
        sb.append(centreProbe(main));
        draws = 0;
        log(sb.toString());
    }

    /**
     * Reads the screen centre back: depth, colour before and after the pass, and the same lighting maths on the
     * CPU. Tells apart "depth empty", "matrix wrong" and "drawn but overwritten".
     */
    private static String centreProbe(RenderTarget main) {
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        FloatBuffer before = CENTRE_BEFORE;
        FloatBuffer after = BufferUtils.createFloatBuffer(4);
        readCentre(depthFbo, main, GL11.GL_DEPTH_COMPONENT, depth);
        readCentre(main.frameBufferId, main, GL11.GL_RGBA, after);

        float d = depth.get(0);
        org.joml.Vector4f p = new org.joml.Vector4f(0, 0, d * 2 - 1, 1).mul(INV_VIEW_PROJ);
        p.div(p.w);
        String surface = "-";
        if (!lastLights.isEmpty() && d < 1f) {
            Light l = lastLights.get(0);
            Vec3 to = new Vec3(p.x, p.y, p.z).subtract(l.origin());
            double dist = to.length();
            double c = to.normalize().dot(l.dir());
            double cone = Mth.clamp((c - COS_OUTER) / (COS_INNER - COS_OUTER), 0, 1);
            double fall = Mth.clamp(1 - dist / RANGE, 0, 1);
            surface = String.format("%.2f (dist %.2f, cos %.3f)", cone * fall * fall * l.intensity(), dist, c);
        }
        return String.format("%n    centre: depth %.5f%s, world offset (%.2f, %.2f, %.2f), CPU surface light %s"
                        + "%n    centre colour before (%.3f, %.3f, %.3f) after (%.3f, %.3f, %.3f)%s",
                d, d >= 1f ? " (SKY / empty depth copy)" : "", p.x, p.y, p.z, surface,
                before.get(0), before.get(1), before.get(2), after.get(0), after.get(1), after.get(2),
                Math.abs(after.get(0) - before.get(0)) + Math.abs(after.get(1) - before.get(1)) < 0.004
                        ? "  <-- UNCHANGED: the pass did not brighten the centre" : "  <-- brightened");
    }

    private static void state(String s) {
        if (!DEBUG_LOG || s.equals(lastState)) return;
        lastState = s;
        log(s);
    }

    private static void log(String s) {
        if (DEBUG_LOG) HalloweenEventMod.LOGGER.info("[Flashlight] {}", s);
    }
}
