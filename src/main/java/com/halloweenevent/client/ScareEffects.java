package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.registry.ModSounds;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.ArrayList;
import java.util.List;

/**
 * All horror visuals and sounds. Driven by {@link S2CScare} triggers; timing, animation and
 * randomness are local so the server sends one tiny packet per scare stage.
 * <p>
 * Shader packs (Oculus): world fog comes from the server-applied Darkness/Blindness effects, which shader
 * packs read via Iris uniforms; the mist, vignette, text, faces and screen glitches are GUI layers and
 * render on top of any shader pack. The glitch post shader is only used when no shader pack is active.
 */
public final class ScareEffects {
    private static final ResourceLocation FACE = tex("textures/misc/horror_face.png");
    private static final ResourceLocation MIST = tex("textures/misc/mist.png");
    private static final ResourceLocation VIGNETTE = tex("textures/misc/vignette.png");
    private static final ResourceLocation STATIC = tex("textures/misc/static.png");
    private static final ResourceLocation SCREEN_HAND = tex("textures/misc/screen_hand.png");
    private static final ResourceLocation GLITCH_POST = tex("shaders/post/glitch.json");
    private static final int HORROR_LINES = 16;
    private static final int MAX_TEXTS = 16;
    private static final RandomSource RNG = RandomSource.create();

    private record Text(Component text, float fx, float fy, int start, int end, int scale) {}

    private static final List<Text> TEXTS = new ArrayList<>();
    private static int tick;
    private static int glitchUntil;
    private static int shakeUntil;
    private static int flashUntil;
    private static int freecamFaceUntil;
    private static boolean carryFace;
    private static int carryFaceStart;

    private static float fog;
    private static float fogTarget;
    private static boolean fogRed;

    private static Vec3 figurePos;
    private static FigureModels.Kind figureKind = FigureModels.Kind.TALL;
    private static int figureStart;
    private static int figureEnd;
    private static int figureLevel;
    private static boolean figureLunged;
    private static boolean figureSeen;

    private static boolean postFailed;

    /** Victim-only chaser after a hand grab. */
    private static Vec3 chasePos;
    private static FigureModels.Kind chaseKind = FigureModels.Kind.TALL;
    private static int chaseEnd;
    private static int nextStep;

    /** Dark figure dragging an entity (victim or partner view). Timings match the server: reach, then drag. */
    private static int handVictim = -1;
    private static float handDirX;
    private static float handDirZ;
    private static int handStart;
    private static FigureModels.Kind grabKind = FigureModels.Kind.TALL;
    private static final int GRAB_REACH = 12;
    private static final int GRAB_END = GRAB_REACH + 24;
    private static final int GRAB_TICKS = GRAB_END + 10;
    /** Victim only: a clawed hand gripping the screen, coming from the side they are dragged to. */
    private static int screenHandStart = -1000;
    private static int screenHandSide = 1;

    private ScareEffects() {}

    private static ResourceLocation tex(String path) {
        return new ResourceLocation(HalloweenEventMod.MODID, path);
    }

    public static void handle(int type, int level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        switch (type) {
            case S2CScare.WHISPER -> {
                whisper(level >= 3);
                fogTo(0.3f, false);
            }
            case S2CScare.TEXT -> {
                whisper(true);
                spawnTexts(level >= 3 ? 8 : 5, 90);
                fogTo(0.6f, level >= 3);
                if (level >= 3) {
                    glitch(40);
                    play(ModSounds.GLITCH.get(), 1.0f, 1.0f);
                }
            }
            case S2CScare.FIGURE -> {
                fogTo(level >= 3 ? 1.0f : 0.85f, level >= 3);
                if (level <= 1) {
                    whisper(true);
                    spawnTexts(6, 90);
                } else {
                    spawnFigure(mc, level);
                }
            }
            case S2CScare.STOP -> stopHandScares();
            case S2CScare.CARRY_ON -> {
                if (!carryFace) {
                    carryFace = true;
                    carryFaceStart = tick;
                    play(ModSounds.SCREAM.get(), 1.0f, 0.8f);
                }
            }
            case S2CScare.CARRY_OFF -> carryFace = false;
            case S2CScare.CHASE -> startChase(mc);
            default -> {}
        }
    }

    public static void freecamCaught() {
        if (tick >= freecamFaceUntil) play(ModSounds.SCREAM.get(), 1.0f, 1.1f);
        freecamFaceUntil = tick + 60;
        glitch(20);
    }

    /** Hand-holding resumed: everything fades out, the fog included. */
    public static void stopHandScares() {
        TEXTS.clear();
        figurePos = null;
        glitchUntil = shakeUntil = flashUntil = tick;
        fogTarget = 0f;
        fogRed = false;
    }

    public static void stopAll() {
        stopHandScares();
        chasePos = null;
        handVictim = -1;
        screenHandStart = -1000;
        carryFace = false;
        freecamFaceUntil = tick;
    }

    // ---- ticking ----

    public static void clientTick(Minecraft mc) {
        tick++;
        fog += (fogTarget - fog) * (fogTarget > fog ? 0.04f : 0.08f);
        if (fog < 0.005f && fogTarget == 0f) fog = 0f;
        if (!TEXTS.isEmpty()) TEXTS.removeIf(t -> tick > t.end());
        Player player = mc.player;
        if (figurePos != null && player != null) tickFigure(mc, player);
        if (chasePos != null && player != null) tickChase(mc, player);
        if (handVictim >= 0) tickGrab(mc);
        if (carryFace && (tick - carryFaceStart) % 25 == 0) play(ModSounds.HEARTBEAT.get(), 1.0f, 1.0f);
        if (tick < glitchUntil && tick % 8 == 0) play(ModSounds.GLITCH.get(), 0.6f, 0.5f + RNG.nextFloat() * 1.0f);
        updatePostEffect(mc);
    }

    private static void tickFigure(Minecraft mc, Player player) {
        if (tick >= figureEnd) {
            figurePos = null;
            return;
        }
        if (figureKind == FigureModels.Kind.CRAWLER && !figureLunged) {
            // It crawls towards you.
            Vec3 to = player.position().subtract(figurePos);
            if (to.horizontalDistanceSqr() > 4) {
                Vec3 step = new Vec3(to.x, 0, to.z).normalize().scale(0.06);
                figurePos = figurePos.add(step);
            }
        }
        if (tick % 3 == 0 && mc.level != null) {
            mc.level.addParticle(ParticleTypes.LARGE_SMOKE, figurePos.x + RNG.nextGaussian() * 0.3,
                    figurePos.y + 0.2 + RNG.nextFloat() * 1.5, figurePos.z + RNG.nextGaussian() * 0.3, 0, 0.02, 0);
        }
        if (figureLevel >= 3 && !figureLunged && tick - figureStart >= 50) {
            // Level 3: it is suddenly in your face.
            Vec3 look = player.getLookAngle();
            figurePos = new Vec3(player.getX() + look.x * 1.8, player.getY(), player.getZ() + look.z * 1.8);
            figureLunged = true;
            figureEnd = tick + 22;
            flashUntil = tick + 8;
            glitch(45);
            shake(30);
            play(ModSounds.SCREAM.get(), 1.0f, 0.7f);
            play(ModSounds.STINGER.get(), 1.0f, 0.5f);
        } else if (figureLevel == 2 && !figureSeen && isLookingAt(player, figurePos)) {
            // Level 2: it vanishes shortly after you notice it.
            figureSeen = true;
            figureEnd = Math.min(figureEnd, tick + 14);
            play(ModSounds.WHISPER_LOUD.get(), 1.0f, 0.6f);
            glitch(10);
        }
    }

    private static boolean isLookingAt(Player player, Vec3 pos) {
        Vec3 to = pos.add(0, 1.2, 0).subtract(player.getEyePosition()).normalize();
        return player.getViewVector(1.0f).dot(to) > 0.96;
    }

    // ---- triggers ----

    /**
     * Whispers are played non-positional on the master channel and layered (left + right + a deep voice),
     * because a single soul-sand whisper sample is too quiet to notice during gameplay.
     */
    private static void whisper(boolean loud) {
        SoundEvent soft = ModSounds.WHISPER.get();
        playPanned(soft, 1.0f, 0.85f + RNG.nextFloat() * 0.1f, -2.0, -1.0);
        playPanned(soft, 1.0f, 0.75f + RNG.nextFloat() * 0.1f, 2.0, -1.0);
        if (loud) playPanned(ModSounds.WHISPER_LOUD.get(), 1.0f, 0.6f, 0.0, -1.5);
    }

    private static void spawnTexts(int n, int duration) {
        for (int i = 0; i < n && TEXTS.size() < MAX_TEXTS; i++) {
            int start = tick + i * 6;
            TEXTS.add(new Text(Component.translatable("horror.halloweenevent." + RNG.nextInt(HORROR_LINES)),
                    RNG.nextFloat(), 0.08f + RNG.nextFloat() * 0.84f, start, start + duration, 1 + RNG.nextInt(2)));
        }
    }

    private static void spawnFigure(Minecraft mc, int level) {
        Player p = mc.player;
        if (p == null || mc.level == null) return;
        FigureModels.Kind[] kinds = FigureModels.Kind.values();
        FigureModels.Kind next = kinds[RNG.nextInt(kinds.length)];
        if (next == figureKind) next = kinds[(next.ordinal() + 1) % kinds.length];
        figureKind = next;

        double yaw = Math.toRadians(p.getYRot() + (RNG.nextBoolean() ? 50 : -50));
        double dist = level >= 3 ? 9 : 7;
        double x = p.getX() - Math.sin(yaw) * dist;
        double z = p.getZ() + Math.cos(yaw) * dist;
        figurePos = new Vec3(x, groundY(mc, x, p.getY() + 2, z), z);
        figureStart = tick;
        figureEnd = tick + 110;
        figureLevel = level;
        figureLunged = false;
        figureSeen = false;
        play(ModSounds.STINGER.get(), 1.0f, 0.8f);
        if (level >= 3) {
            glitch(30);
            shake(20);
        }
    }

    /** Highest standing spot at or below {@code startY} (up to 8 blocks down). */
    private static double groundY(Minecraft mc, double x, double startY, double z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, startY, z);
        for (int i = 0; i < 8; i++) {
            BlockPos below = m.below();
            if (!mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()) break;
            m.move(Direction.DOWN);
        }
        return m.getY();
    }

    private static void spawnText(String key, int duration) {
        if (TEXTS.size() >= MAX_TEXTS) return;
        TEXTS.add(new Text(Component.translatable(key), 0.5f, 0.3f + RNG.nextFloat() * 0.4f, tick, tick + duration, 2));
    }

    // ---- hand grab + chase ----

    public static void handGrab(int victimId, float dirX, float dirZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getEntity(victimId) == null) return;
        handVictim = victimId;
        handDirX = dirX;
        handDirZ = dirZ;
        handStart = tick;
        FigureModels.Kind[] pullers = {FigureModels.Kind.TALL, FigureModels.Kind.SMILER, FigureModels.Kind.BROKEN};
        grabKind = pullers[RNG.nextInt(pullers.length)];
        if (mc.player != null && mc.player.getId() == victimId) {
            Vec3 look = mc.player.getLookAngle();
            // Screen right is (-look.z, look.x); the hand comes from the side the figure pulls towards.
            screenHandSide = dirX * -look.z + dirZ * look.x >= 0 ? 1 : -1;
            screenHandStart = tick;
            play(ModSounds.STINGER.get(), 1.0f, 0.6f);
            glitch(20);
            shake(GRAB_END);
            spawnText("horror.halloweenevent.grab", 50);
        }
    }

    private static void tickGrab(Minecraft mc) {
        int age = tick - handStart;
        net.minecraft.world.entity.Entity victim = mc.level == null ? null : mc.level.getEntity(handVictim);
        if (age > GRAB_TICKS || victim == null) {
            handVictim = -1;
            return;
        }
        if (age % 2 == 0) {
            Vec3 f = grabFigurePos(victim, 0f, age);
            mc.level.addParticle(ParticleTypes.LARGE_SMOKE, f.x + RNG.nextGaussian() * 0.3,
                    f.y + 0.2 + RNG.nextFloat() * 1.6, f.z + RNG.nextGaussian() * 0.3, 0, 0.03, 0);
        }
    }

    /** Emerges 2.6 blocks out, closes to 1 block while reaching, then stays just ahead of the victim. */
    private static Vec3 grabFigurePos(net.minecraft.world.entity.Entity victim, float pt, float age) {
        float off = age < GRAB_REACH ? Mth.lerp(age / GRAB_REACH, 2.6f, 1.0f) : 1.0f;
        Vec3 v = victim.getPosition(pt);
        return new Vec3(v.x + handDirX * off, v.y, v.z + handDirZ * off);
    }

    private static void startChase(Minecraft mc) {
        Player p = mc.player;
        if (p == null || mc.level == null) return;
        Vec3 look = p.getLookAngle();
        Vec3 back = new Vec3(-look.x, 0, -look.z);
        back = back.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : back.normalize();
        double x = p.getX() + back.x * 8, z = p.getZ() + back.z * 8;
        chasePos = new Vec3(x, groundY(mc, x, p.getY() + 3, z), z);
        FigureModels.Kind[] kinds = FigureModels.Kind.values();
        chaseKind = kinds[RNG.nextInt(kinds.length)];
        chaseEnd = tick + 200;
        nextStep = tick;
        play(ModSounds.STINGER.get(), 1.0f, 0.6f);
        play(ModSounds.SCREAM.get(), 0.8f, 0.5f);
        spawnText("horror.halloweenevent.chase_start", 40);
        fogTo(0.7f, true);
    }

    /** Straight-line pursuit at just under sprint speed; passes through walls (it is a hallucination). */
    private static void tickChase(Minecraft mc, Player player) {
        if (tick >= chaseEnd) {
            chasePos = null;
            whisper(true);
            spawnText("horror.halloweenevent.chase_end", 60);
            return;
        }
        Vec3 to = player.position().subtract(chasePos);
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        if (dist < 1.2 && Math.abs(to.y) < 2.5) {
            chasePos = null;
            flashUntil = tick + 10;
            glitch(30);
            shake(25);
            play(ModSounds.SCREAM.get(), 1.0f, 0.9f);
            return;
        }
        double nx = chasePos.x + to.x / dist * 0.26, nz = chasePos.z + to.z / dist * 0.26;
        chasePos = new Vec3(nx, groundY(mc, nx, Math.max(chasePos.y, player.getY()) + 2, nz), nz);
        if (tick >= nextStep) {
            playAt(ModSounds.FOOTSTEP.get(), chasePos, 1.0f, 0.9f + RNG.nextFloat() * 0.2f);
            nextStep = tick + (int) Mth.clamp(dist / 1.5, 3, 9);
        }
        if (dist < 6 && tick % 10 == 0) play(ModSounds.HEARTBEAT.get(), 1.0f, 1.2f);
        if (tick % 2 == 0) {
            mc.level.addParticle(ParticleTypes.LARGE_SMOKE, chasePos.x + RNG.nextGaussian() * 0.3,
                    chasePos.y + 0.3 + RNG.nextFloat() * 1.4, chasePos.z + RNG.nextGaussian() * 0.3, 0, 0.03, 0);
        }
    }

    private static void fogTo(float value, boolean red) {
        fogTarget = Math.max(fogTarget, value);
        if (red) fogRed = true;
    }

    private static void glitch(int ticks) {
        glitchUntil = Math.max(glitchUntil, tick + ticks);
    }

    private static void shake(int ticks) {
        shakeUntil = Math.max(shakeUntil, tick + ticks);
    }

    private static void play(SoundEvent sound, float volume, float pitch) {
        playPanned(sound, volume, pitch, 0, 0);
    }

    /** World-positioned sound, so footsteps come from where the chaser is. */
    private static void playAt(SoundEvent sound, Vec3 pos, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.MASTER,
                volume, pitch, RNG, pos.x, pos.y, pos.z));
    }

    /** Non-attenuated sound relative to the listener: full volume, but panned to the given side. */
    private static void playPanned(SoundEvent sound, float volume, float pitch, double x, double z) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound.getLocation(), SoundSource.MASTER,
                volume, pitch, RNG, false, 0, SoundInstance.Attenuation.NONE, x, 0, z, true));
    }

    // ---- glitch post shader (vanilla renderer only) ----

    private static void updatePostEffect(Minecraft mc) {
        if (postFailed) return;
        boolean want = tick < glitchUntil && !ShaderCompat.shaderPackInUse();
        PostChain current = mc.gameRenderer.currentEffect();
        boolean ours = current != null && GLITCH_POST.toString().equals(current.getName());
        if (want && current == null) {
            mc.gameRenderer.loadEffect(GLITCH_POST);
            if (mc.gameRenderer.currentEffect() == null) {
                postFailed = true;
                HalloweenEventMod.LOGGER.warn("Glitch post shader could not be loaded; using the overlay glitch only");
            }
        } else if (!want && ours) {
            mc.gameRenderer.shutdownEffect();
        }
    }

    // ---- fog ----

    /** Extra fog from the scare ladder, 0..1; the zone fog itself is drawn by {@link ZoneFogRenderer}. */
    public static float fogLevel() {
        return fog;
    }

    public static boolean fogRed() {
        return fogRed;
    }

    /** Fallback only: vanilla fog is used when the depth-fog shaders could not be loaded. */
    public static void renderFog(ViewportEvent.RenderFog event) {
        if (ZoneFogRenderer.active() || fog < 0.01f) return;
        float far = Mth.lerp(fog, event.getFarPlaneDistance(), 5f);
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), far));
        event.setNearPlaneDistance(event.getMode() == FogRenderer.FogMode.FOG_SKY ? 0f : Math.min(event.getNearPlaneDistance(), far * 0.05f));
        event.setCanceled(true);
    }

    public static void fogColor(ViewportEvent.ComputeFogColor event) {
        if (ZoneFogRenderer.active() || fog < 0.01f) return;
        float r = fogRed ? 0.12f : 0.03f, g = 0.02f, b = fogRed ? 0.02f : 0.04f;
        event.setRed(Mth.lerp(fog, event.getRed(), r));
        event.setGreen(Mth.lerp(fog, event.getGreen(), g));
        event.setBlue(Mth.lerp(fog, event.getBlue(), b));
    }

    // ---- rendering ----

    /** HUD layer (registered above all vanilla overlays). */
    public static void renderOverlay(ForgeGui gui, GuiGraphics g, float partialTick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float time = tick + partialTick;

        if (ZoneFogRenderer.active()) {
            // Textured mist drifting over the 3D fog: always on in the zone, lighter while holding a hand.
            // Edges stay faint normally and only darken during scares.
            renderMist(g, time, w, h, Math.min(1f, ZoneFogRenderer.strength() * 0.3f + fog * 0.3f),
                    ZoneFogRenderer.brightness(), Math.min(0.8f, ZoneFogRenderer.strength() * 0.2f + fog * 0.5f));
        } else if (fog > 0.01f) {
            renderMist(g, time, w, h, fog, 1f, Math.min(1f, fog * 1.1f));
        }

        for (Text t : TEXTS) {
            int age = tick - t.start();
            int life = t.end() - t.start();
            float a = Math.min(1f, Math.min(age, life - age) / 10f);
            if (a <= 0.04f) continue;
            int tw = font.width(t.text()) * t.scale();
            int x = (int) ((w - tw) * t.fx()) + RNG.nextInt(3) - 1;
            int y = (int) ((h - 9 * t.scale()) * t.fy()) + RNG.nextInt(3) - 1;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(t.scale(), t.scale(), 1);
            g.drawString(font, t.text(), 0, 0, ((int) (a * 255) << 24) | 0xC00000, true);
            g.pose().popPose();
        }

        if (tick < glitchUntil) renderGlitch(g, partialTick, w, h);
        if (tick - screenHandStart < GRAB_TICKS) renderScreenHand(g, tick - screenHandStart + partialTick, w, h);

        boolean freecamFace = tick < freecamFaceUntil;
        boolean flash = tick < flashUntil;
        if (carryFace || freecamFace || flash) {
            boolean full = freecamFace || flash;
            float alpha = full ? 1f : 0.7f + 0.15f * Mth.sin(time * 0.4f);
            int size = (int) (Math.max(w, h) * (full ? 1.15f : 1.05f + 0.03f * Mth.sin(time * 0.7f)));
            int x = (w - size) / 2 + RNG.nextInt(5) - 2;
            int y = (h - size) / 2 + RNG.nextInt(5) - 2;
            if (full) g.fill(0, 0, w, h, 0xFF000000);
            RenderSystem.enableBlend();
            g.setColor(1f, 1f, 1f, alpha);
            g.blit(FACE, x, y, size, size, 0, 0, 256, 256, 256, 256);
            g.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }
    }

    /**
     * Victim view: a black clawed hand slides in from the side, grips and shakes while dragging, then lets go.
     * The texture has the wrist on the left; it is mirrored for the right side.
     */
    private static void renderScreenHand(GuiGraphics g, float age, int w, int h) {
        float show = Mth.clamp(Math.min(age / 6f, (GRAB_TICKS - age) / 8f), 0f, 1f);
        show = show * show * (3f - 2f * show);
        if (show <= 0f) return;
        boolean dragging = age >= GRAB_REACH && age < GRAB_END;
        int size = (int) (h * 1.3f);
        int reach = (int) (size * 0.8f * show);
        int jx = dragging ? RNG.nextInt(9) - 4 : 0;
        int jy = dragging ? RNG.nextInt(9) - 4 : 0;
        int y = (h - size) / 2 + (int) (h * 0.08f) + jy;
        RenderSystem.enableBlend();
        g.setColor(0.35f, 0f, 0f, 0.75f * show);
        g.blit(VIGNETTE, 0, 0, w, h, 0, 0, 256, 256, 256, 256);
        g.setColor(1f, 1f, 1f, Math.min(1f, show * 1.4f));
        if (screenHandSide < 0) {
            g.blit(SCREEN_HAND, -size + reach + jx, y, size, size, 0, 0, 256, 256, 256, 256);
        } else {
            g.blit(SCREEN_HAND, w - reach + jx, y, size, size, 256, 0, -256, 256, 256, 256);
        }
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /**
     * Two drifting layers of the cloudy mist texture plus screen-edge darkening. GUI-space, so it shows with or
     * without shader packs.
     *
     * @param strength   mist opacity 0..1
     * @param brightness 0..1, dims the mist at night
     * @param vignette   edge darkening 0..1
     */
    private static void renderMist(GuiGraphics g, float time, int w, int h, float strength, float brightness, float vignette) {
        RenderSystem.enableBlend();
        float r = (fogRed ? 0.75f : 0.8f) * brightness, gb = (fogRed ? 0.55f : 0.82f) * brightness;
        g.setColor(r, gb, gb, strength * 0.45f);
        g.blit(MIST, 0, 0, w, h, time * 0.35f, time * 0.08f, w / 2, h / 2, 128, 128);
        g.setColor(r, gb, gb, strength * 0.3f);
        g.blit(MIST, 0, 0, w, h, -time * 0.22f, time * 0.15f + 40, w / 3, h / 3, 128, 128);
        if (vignette > 0.01f) {
            g.setColor(fogRed ? 0.25f : 0f, 0f, 0f, vignette);
            g.blit(VIGNETTE, 0, 0, w, h, 0, 0, 256, 256, 256, 256);
        }
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** TV static strips, colour-channel bars, corrupted blocks, scanlines and dropped frames. */
    private static void renderGlitch(GuiGraphics g, float partialTick, int w, int h) {
        RandomSource r = RandomSource.create(tick * 31L + (long) (partialTick * 3));
        RenderSystem.enableBlend();
        int strips = 2 + r.nextInt(5);
        for (int i = 0; i < strips; i++) {
            int y = r.nextInt(h);
            int sh = 2 + r.nextInt(16);
            g.setColor(1f, 1f, 1f, 0.35f + r.nextFloat() * 0.45f);
            g.blit(STATIC, r.nextInt(30) - 15, y, w + 30, sh, r.nextInt(64), r.nextInt(64), (w + 30) / 2, Math.max(1, sh / 2), 64, 64);
        }
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();

        int bars = 4 + r.nextInt(8);
        for (int i = 0; i < bars; i++) {
            int y = r.nextInt(h);
            int bh = 1 + r.nextInt(5);
            int off = r.nextInt(60) - 30;
            int color = switch (r.nextInt(3)) {
                case 0 -> 0x66FF0000;
                case 1 -> 0x6600FFFF;
                default -> 0x5500FF00;
            };
            g.fill(off, y, w + off, y + bh, color);
        }
        int blocks = 3 + r.nextInt(8);
        for (int i = 0; i < blocks; i++) {
            int bw = 8 + r.nextInt(70), bh = 4 + r.nextInt(24);
            int x = r.nextInt(Math.max(1, w - bw)), y = r.nextInt(Math.max(1, h - bh));
            int color = 0xA0000000 | (r.nextInt(2) == 0 ? 0xFFFFFF & (r.nextInt() | 0x404040) : 0x000000);
            g.fill(x, y, x + bw, y + bh, color);
        }
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x30000000);
        if (r.nextInt(10) == 0) g.fill(0, 0, w, h, 0xD0000000);
    }

    /** World pass: the shadow figure, built from cuboids. Flickers while it fades in. */
    public static void renderFigure(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        float pt = event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        if (chasePos != null) {
            FigureModels.render(event.getPoseStack(), mc.renderBuffers().bufferSource(), chaseKind, chasePos, cam,
                    cam, tick + pt, 1f, false, true);
        }
        if (handVictim >= 0 && mc.level != null) renderGrabFigure(event, mc, cam, pt);
        if (figurePos == null) return;
        float age = tick - figureStart + pt;
        float remaining = figureEnd - tick - pt;
        float alpha = Mth.clamp(Math.min(age / 12f, remaining / 6f), 0f, 1f);
        if (alpha <= 0f) return;
        if (age < 30 && RNG.nextInt(5) == 0) return;
        FigureModels.render(event.getPoseStack(), mc.renderBuffers().bufferSource(), figureKind, figurePos,
                cam, cam, tick + pt, alpha, figureLunged, false);
    }

    /** The dragging figure faces the victim, arms out; its legs run while it pulls. Both partners see it. */
    private static void renderGrabFigure(RenderLevelStageEvent event, Minecraft mc, Vec3 cam, float pt) {
        net.minecraft.world.entity.Entity victim = mc.level.getEntity(handVictim);
        if (victim == null) return;
        float age = tick - handStart + pt;
        float alpha = Mth.clamp(Math.min(age / 6f, (GRAB_TICKS - age) / 8f), 0f, 1f);
        if (alpha <= 0f) return;
        Vec3 pos = grabFigurePos(victim, pt, age);
        FigureModels.render(event.getPoseStack(), mc.renderBuffers().bufferSource(), grabKind, pos, cam,
                victim.getPosition(pt), tick + pt, alpha, true, age >= GRAB_REACH && age < GRAB_END);
    }

    public static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (tick >= shakeUntil) return;
        float t = tick + (float) event.getPartialTick();
        float amp = 2.5f * Math.min(1f, (shakeUntil - tick) / 30f);
        event.setRoll(event.getRoll() + Mth.sin(t * 2.1f) * amp);
        event.setYaw(event.getYaw() + Mth.sin(t * 3.7f) * amp * 0.4f);
        event.setPitch(event.getPitch() + Mth.cos(t * 2.9f) * amp * 0.3f);
    }
}
