package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/**
 * Shadow figures built from cuboids, exactly like vanilla mob models (ModelPart), in vanilla model space:
 * 16 units per block, y pointing down, feet at y = 24, face on the -z side.
 * <p>
 * Every figure has real eye parts ({@code head/eye_l}, {@code head/eye_r}, each with a {@code pupil}) that blink,
 * glance away and snap back to stare at you, pulse, and go wide when the figure lunges.
 * Texture atlas: see the region constants below and {@code tools/gen_textures.mjs}.
 */
public final class FigureModels {
    public enum Kind {
        /** Very tall, floating ragged cloak, wide-brim hat, long pale grinning mask. */
        HATTED(0.95f, 0.45f),
        /** Tall and thin, red ribbed flesh fading to black, long neck, tiny head with dot eyes. */
        RED_THIN(0.9f, 0.15f),
        /** Hunched black furry beast: floppy ears, big round red eyes, wide toothy grin. */
        GRINNER(0.85f, 0.55f),
        /** On all fours, spine ridges, face turned up at you. */
        CRAWLER(1.0f, 0.3f);

        final float scale;
        /** How far the pupils dart sideways, in model units. */
        final float gaze;

        Kind(float scale, float gaze) {
            this.scale = scale;
            this.gaze = gaze;
        }
    }

    private static final ResourceLocation SKIN = new ResourceLocation(HalloweenEventMod.MODID, "textures/entity/figure_skin.png");
    private static final Map<Kind, ModelPart> MODELS = new EnumMap<>(Kind.class);

    // Texture atlas regions (u, v) in figure_skin.png (256x256).
    private static final int[] DARK = {0, 0};
    private static final int[] VOID = {96, 32};
    private static final int[] RED = {96, 64};
    private static final int[] WHITE = {96, 96};
    private static final int[] FLESH = {128, 0};
    private static final int[] FUR = {128, 64};
    private static final int[] MASK = {0, 128};
    private static final int[] GUM = {64, 128};
    private static final int[] GLOW = {96, 128};
    private static final int[] CLOAK = {128, 128};
    private static final int[] LIMB = {0, 192};

    private static final int HATTED_RAGS = 12;

    private FigureModels() {}

    // ---- rendering ----

    /**
     * @param time    client ticks + partial tick, drives idle animation, blinking and twitches
     * @param lunge   true while the figure is jumping at the player
     * @param running sprinting legs (and arms, unless lunging), leaning forward
     * @param lookAt  the point the figure faces (the camera, or the victim it is dragging)
     */
    public static void render(PoseStack ps, MultiBufferSource.BufferSource buffers, Kind kind, Vec3 pos, Vec3 cam,
                              Vec3 lookAt, float time, float alpha, boolean lunge, boolean running) {
        ModelPart root = MODELS.computeIfAbsent(kind, FigureModels::build);
        root.getAllParts().forEach(ModelPart::resetPose);
        animate(kind, root, time, lunge);
        animateEyes(kind, root.getChild("head"), time, lunge);
        if (running) animateRun(kind, root, time, !lunge);

        ps.pushPose();
        ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-(lookAt.x - pos.x), lookAt.z - pos.z));
        ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        // Forward is -z here (before the model flip), so a negative X rotation leans the figure towards you.
        if (running) ps.mulPose(Axis.XP.rotationDegrees(-14f));
        float s = kind.scale * (lunge ? 1.15f : 1f);
        ps.scale(s, s, s);
        ps.scale(-1f, -1f, 1f);
        ps.translate(0, -1.501, 0);
        RenderType type = RenderType.entityTranslucent(SKIN);
        VertexConsumer vc = buffers.getBuffer(type);
        root.render(ps, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, alpha);
        ps.popPose();
        buffers.endBatch(type);
    }

    /** Pseudo-random snap of the head every few ticks; same value for a whole 7-tick window. */
    private static float twitch(float time, int salt) {
        int window = Mth.floor(time / 7f);
        float h = hash(window * 31 + salt);
        return h > 0.72f ? (hash(window * 17 + salt + 5) - 0.5f) * 0.9f : 0f;
    }

    private static float hash(int n) {
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    // ---- animation ----

    private static void animate(Kind kind, ModelPart root, float t, boolean lunge) {
        ModelPart head = root.getChild("head");
        ModelPart armL = root.getChild("arm_l");
        ModelPart armR = root.getChild("arm_r");
        switch (kind) {
            case HATTED -> {
                root.y += Mth.sin(t * 0.06f) * 0.8f;                         // drifts up and down: it floats
                head.zRot += Mth.sin(t * 0.025f) * 0.28f + twitch(t, 1) * 0.6f; // slow curious tilt
                head.xRot += 0.06f;
                ModelPart body = root.getChild("body");
                for (int i = 0; i < HATTED_RAGS; i++) {
                    ModelPart rag = body.getChild("rag" + i);
                    rag.xRot += Mth.sin(t * 0.08f + i * 1.7f) * 0.14f;
                    rag.zRot += Mth.cos(t * 0.07f + i * 2.3f) * 0.1f;
                }
                float sway = Mth.sin(t * 0.04f) * 0.05f;
                armL.xRot += lunge ? -1.45f : sway;
                armR.xRot += lunge ? -1.45f : -sway;
            }
            case RED_THIN -> {
                head.yRot += Mth.sin(t * 0.03f) * 0.5f + twitch(t, 2) * 0.8f;  // turns its head too far, too slowly
                head.zRot += twitch(t, 3) * 0.3f;
                float sway = Mth.sin(t * 0.05f) * 0.04f;
                armL.xRot += lunge ? -1.45f : sway;
                armR.xRot += lunge ? -1.45f : -sway;
                if (lunge) head.xRot -= 0.3f;
            }
            case GRINNER -> {
                float tilt = Mth.sin(t * 0.04f) * 0.45f;                       // dog-like head tilt
                head.zRot += tilt + twitch(t, 4) * 0.4f;
                head.getChild("ear_l").zRot -= tilt * 0.6f;
                head.getChild("ear_r").zRot -= tilt * 0.6f;
                root.getChild("body").xRot += Mth.sin(t * 0.1f) * 0.03f;     // breathing
                if (lunge) {
                    armL.xRot -= 1.5f;
                    armR.xRot -= 1.5f;
                    head.xRot -= 0.2f;
                }
            }
            case CRAWLER -> {
                head.yRot += Mth.sin(t * 0.07f) * 0.25f + twitch(t, 6);
                head.zRot += twitch(t, 7);
                float step = Mth.sin(t * 0.35f) * 0.25f;
                armL.xRot += step;
                armR.xRot -= step;
                root.getChild("leg_l").xRot -= step;
                root.getChild("leg_r").xRot += step;
                if (lunge) head.xRot -= 0.5f;
            }
        }
    }

    /**
     * Blink every few seconds, mostly stare straight at you with occasional darting glances,
     * slowly pulsing pupils; when lunging the eyes go wide and the pupils pin-prick.
     * Scale is not part of a pose, so it is set explicitly every frame.
     */
    private static void animateEyes(Kind kind, ModelPart head, float t, boolean lunge) {
        int blinkWindow = Mth.floor(t / 50f);
        boolean blink = kind != Kind.RED_THIN && !lunge
                && hash(blinkWindow * 7 + kind.ordinal()) > 0.3f && t - blinkWindow * 50f < 3f;
        float open = blink ? 0.1f : 1f;
        float wide = lunge ? 1.3f : 1f;
        int glance = Mth.floor(t / 12f);
        float px = 0f, py = 0f;
        if (!lunge && hash(glance * 13 + 3) > 0.55f) {
            px = (hash(glance * 5 + 1) - 0.5f) * 2f * kind.gaze;
            py = (hash(glance * 9 + 2) - 0.5f) * kind.gaze;
        }
        float pupil = lunge ? 0.6f : 1f + 0.12f * Mth.sin(t * 0.15f);
        for (String name : new String[]{"eye_l", "eye_r"}) {
            ModelPart eye = head.getChild(name);
            eye.xScale = wide;
            eye.yScale = open * wide;
            eye.zScale = 1f;
            ModelPart p = eye.getChild("pupil");
            p.x = px;
            p.y = py;
            p.xScale = p.yScale = pupil;
            p.zScale = 1f;
        }
    }

    private static void animateRun(Kind kind, ModelPart root, float t, boolean arms) {
        float swing = Mth.sin(t * 0.9f);
        float legs = kind == Kind.CRAWLER ? 0.5f : 0.9f;
        root.getChild("leg_l").xRot += swing * legs;
        root.getChild("leg_r").xRot -= swing * legs;
        if (arms) {
            root.getChild("arm_l").xRot -= swing * 1.1f;
            root.getChild("arm_r").xRot += swing * 1.1f;
        }
        root.getChild("head").xRot += Mth.sin(t * 1.8f) * 0.08f;
    }

    // ---- model definitions ----

    private static ModelPart build(Kind kind) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        switch (kind) {
            case HATTED -> hatted(root);
            case RED_THIN -> redThin(root);
            case GRINNER -> grinner(root);
            case CRAWLER -> crawler(root);
        }
        return LayerDefinition.create(mesh, 256, 256).bakeRoot();
    }

    private static CubeListBuilder tex(int[] region) {
        return CubeListBuilder.create().texOffs(region[0], region[1]);
    }

    private static CubeListBuilder tex(int u, int v) {
        return CubeListBuilder.create().texOffs(u, v);
    }

    /**
     * An animated eye on a face whose front is at {@code front} (a -z value): a sclera cube with a pupil child
     * sitting just in front of it, optionally with a white glint.
     */
    private static void eye(PartDefinition head, String name, float x, float y, float front, float w, float h,
                            int[] sclera, float pupilSize, int[] pupilTex, boolean glint) {
        PartDefinition eye = head.addOrReplaceChild(name,
                tex(sclera).addBox(-w / 2, -h / 2, -0.15f, w, h, 0.3f), PartPose.offset(x, y, front - 0.15f));
        CubeListBuilder pupil = tex(pupilTex).addBox(-pupilSize / 2, -pupilSize / 2, -0.1f, pupilSize, pupilSize, 0.1f);
        if (glint) {
            pupil.texOffs(WHITE[0], WHITE[1]).addBox(-pupilSize * 0.35f, -pupilSize * 0.35f, -0.16f,
                    pupilSize * 0.3f, pupilSize * 0.3f, 0.06f);
        }
        eye.addOrReplaceChild("pupil", pupil, PartPose.offset(0, 0, -0.16f));
    }

    /** Curved grin: mouth segments (black or red gums) with white teeth above and below, corners pulled up. */
    private static void grin(CubeListBuilder b, float front, float cy, float halfWidth, float curve, int teeth, int[] mouth) {
        float step = 2 * halfWidth / teeth;
        for (int i = 0; i < teeth; i++) {
            float x = -halfWidth + (i + 0.5f) * step;
            float k = x / halfWidth;
            float y = cy - curve * k * k;
            float open = 1.6f - 0.8f * k * k;
            b.texOffs(mouth[0], mouth[1]).addBox(x - step / 2, y - open / 2, front - 0.2f, step, open, 0.2f);
            b.texOffs(WHITE[0], WHITE[1]).addBox(x - step * 0.3f, y - open / 2, front - 0.35f, step * 0.6f, open * 0.45f, 0.15f);
            b.texOffs(WHITE[0], WHITE[1]).addBox(x - step * 0.25f, y + open * 0.1f, front - 0.35f, step * 0.5f, open * 0.4f, 0.15f);
        }
    }

    private static void fingers(PartDefinition hand, int[] region, float length, float spread) {
        for (int i = 0; i < 4; i++) {
            float off = (i - 1.5f) * 0.45f;
            hand.addOrReplaceChild("f" + i, tex(region).addBox(-0.2f, 0, -0.2f, 0.4f, length, 0.4f),
                    PartPose.offsetAndRotation(off, 0, 0, 0.1f, 0, (i - 1.5f) * spread));
        }
    }

    /** ~3 blocks: floating ragged cloak, wide-brim hat with a red band, long pale mask with red-rimmed eyes. */
    private static void hatted(PartDefinition root) {
        PartDefinition body = root.addOrReplaceChild("body", tex(CLOAK)
                        .addBox(-6, -26, -3.5f, 12, 18, 7)
                        .texOffs(128, 160).addBox(-8, -8, -4.5f, 16, 28, 9)
                        .texOffs(200, 140).addBox(-9, -27, -3, 3, 2, 6)
                        .addBox(6, -27, -3, 3, 2, 6),
                PartPose.ZERO);
        for (int i = 0; i < HATTED_RAGS; i++) {
            boolean front = i % 2 == 0;
            float x = -7.2f + (i / 2) * 2.9f;
            float len = 6 + (i * 37 % 7);
            body.addOrReplaceChild("rag" + i, tex(200, 128).addBox(-0.4f, 0, -0.4f, 0.8f, len, 0.8f),
                    PartPose.offset(x, 19.5f, front ? -4.2f : 4.2f));
        }
        root.addOrReplaceChild("leg_l", tex(240, 140).addBox(-1, 0, -1, 2, 4, 2), PartPose.offset(-2, 20, 0));
        root.addOrReplaceChild("leg_r", tex(240, 140).addBox(-1, 0, -1, 2, 4, 2), PartPose.offset(2, 20, 0));
        PartDefinition armL = root.addOrReplaceChild("arm_l", tex(224, 128).addBox(-0.8f, 0, -0.8f, 1.6f, 30, 1.6f),
                PartPose.offsetAndRotation(-6.8f, -25, 0, 0, 0, 0.06f));
        PartDefinition armR = root.addOrReplaceChild("arm_r", tex(224, 128).addBox(-0.8f, 0, -0.8f, 1.6f, 30, 1.6f),
                PartPose.offsetAndRotation(6.8f, -25, 0, 0, 0, -0.06f));
        fingers(armL.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 30, 0)), CLOAK, 6, 0.15f);
        fingers(armR.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 30, 0)), CLOAK, 6, 0.15f);

        CubeListBuilder head = tex(244, 150).addBox(-1, -3, -1, 2, 3, 2)
                .texOffs(MASK[0], MASK[1]).addBox(-3, -14, -3, 6, 11, 6)
                .texOffs(0, 150).addBox(-2, -3.5f, -3, 4, 2.5f, 4)                 // narrow pointed chin
                .texOffs(128, 200).addBox(-3.6f, -12, -2.6f, 0.6f, 14, 5)          // long black hair
                .addBox(3, -12, -2.6f, 0.6f, 14, 5)
                .addBox(-3.4f, -12, 2.6f, 6.8f, 13, 0.6f)
                .texOffs(128, 220).addBox(-9, -14.5f, -9, 18, 1, 18)              // brim
                .texOffs(200, 220).addBox(-3.5f, -20.5f, -3.5f, 7, 6, 7)          // crown
                .texOffs(RED[0], RED[1]).addBox(-3.6f, -15.6f, -3.6f, 7.2f, 1, 7.2f) // red band
                .addBox(-0.5f, -18.5f, -3.7f, 1, 1, 0.2f);                         // red mark on the crown
        grin(head, -3, -6, 2.6f, 0.9f, 9, GUM);
        PartDefinition headPart = root.addOrReplaceChild("head", head, PartPose.offset(0, -26, 0));
        eye(headPart, "eye_l", -1.4f, -9.5f, -3, 1.7f, 0.9f, RED, 0.75f, VOID, true);
        eye(headPart, "eye_r", 1.4f, -9.5f, -3, 1.7f, 0.9f, RED, 0.75f, VOID, true);
    }

    /** ~3.4 blocks: red ribbed torso, limbs fading to black, very long neck, tiny head with dot eyes. */
    private static void redThin(PartDefinition root) {
        root.addOrReplaceChild("leg_l", tex(LIMB).addBox(-1.1f, 0, -1.1f, 2.2f, 28, 2.2f), PartPose.offset(-1.8f, -4, 0));
        root.addOrReplaceChild("leg_r", tex(LIMB).addBox(-1.1f, 0, -1.1f, 2.2f, 28, 2.2f), PartPose.offset(1.8f, -4, 0));
        root.addOrReplaceChild("body", tex(FLESH).addBox(-4, -18, -2, 8, 18, 4)
                .texOffs(200, 0).addBox(-4.5f, -19, -2, 9, 2, 4), PartPose.offset(0, -4, 0));
        PartDefinition armL = root.addOrReplaceChild("arm_l", tex(16, 192).addBox(-1, 0, -1, 2, 32, 2),
                PartPose.offsetAndRotation(-5.2f, -22, 0, 0, 0, 0.05f));
        PartDefinition armR = root.addOrReplaceChild("arm_r", tex(16, 192).addBox(-1, 0, -1, 2, 32, 2),
                PartPose.offsetAndRotation(5.2f, -22, 0, 0, 0, -0.05f));
        fingers(armL.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 32, 0)), LIMB, 6, 0.18f);
        fingers(armR.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 32, 0)), LIMB, 6, 0.18f);
        CubeListBuilder head = tex(160, 0).addBox(-1.2f, -8, -1.2f, 2.4f, 8, 2.4f)
                .texOffs(176, 0).addBox(-2.5f, -14, -3, 5, 6, 5)
                .texOffs(VOID[0], VOID[1]).addBox(-0.8f, -9.6f, -3.1f, 1.6f, 0.3f, 0.2f);   // thin slit mouth
        PartDefinition headPart = root.addOrReplaceChild("head", head, PartPose.offset(0, -23, 0));
        eye(headPart, "eye_l", -1.1f, -11.6f, -3, 0.9f, 0.9f, VOID, 0.3f, WHITE, false);
        eye(headPart, "eye_r", 1.1f, -11.6f, -3, 0.9f, 0.9f, VOID, 0.3f, WHITE, false);
    }

    /** Hunched furry beast with floppy ears, big round glowing red eyes and a wide grin of small teeth. */
    private static void grinner(PartDefinition root) {
        root.addOrReplaceChild("leg_l", tex(FUR).addBox(-1.5f, 0, -1.5f, 3, 16, 3), PartPose.offset(-2.2f, 8, 0));
        root.addOrReplaceChild("leg_r", tex(FUR).addBox(-1.5f, 0, -1.5f, 3, 16, 3), PartPose.offset(2.2f, 8, 0));
        root.addOrReplaceChild("body", tex(144, 64).addBox(-5, -16, -3, 10, 16, 6),
                PartPose.offsetAndRotation(0, 8, 0, 0.25f, 0, 0));
        PartDefinition armL = root.addOrReplaceChild("arm_l", tex(180, 64).addBox(-1.2f, 0, -1.2f, 2.4f, 18, 2.4f),
                PartPose.offsetAndRotation(-6, -5, -1.5f, -0.15f, 0, 0.12f));
        PartDefinition armR = root.addOrReplaceChild("arm_r", tex(180, 64).addBox(-1.2f, 0, -1.2f, 2.4f, 18, 2.4f),
                PartPose.offsetAndRotation(6, -5, -1.5f, -0.15f, 0, -0.12f));
        fingers(armL.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 18, 0)), WHITE, 2.5f, 0.3f);
        fingers(armR.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 18, 0)), WHITE, 2.5f, 0.3f);
        CubeListBuilder head = tex(196, 64).addBox(-1.5f, -2, -1.5f, 3, 2, 3)
                .texOffs(128, 96).addBox(-5, -11, -5, 10, 10, 9)
                .texOffs(VOID[0], VOID[1]).addBox(-0.6f, -4.8f, -5.2f, 1.2f, 0.9f, 0.3f);    // nose
        grin(head, -5, -2.6f, 3.9f, 1.3f, 14, VOID);
        PartDefinition headPart = root.addOrReplaceChild("head", head, PartPose.offset(0, -8, -2.5f));
        headPart.addOrReplaceChild("ear_l", tex(170, 96).addBox(-1, -1, -2.5f, 2, 12, 5),
                PartPose.offsetAndRotation(-5, -9, 0.5f, 0, 0, 0.35f));
        headPart.addOrReplaceChild("ear_r", tex(170, 96).addBox(-1, -1, -2.5f, 2, 12, 5),
                PartPose.offsetAndRotation(5, -9, 0.5f, 0, 0, -0.35f));
        eye(headPart, "eye_l", -2.3f, -7, -5, 2.6f, 2.6f, GLOW, 1.3f, VOID, true);
        eye(headPart, "eye_r", 2.3f, -7, -5, 2.6f, 2.6f, GLOW, 1.3f, VOID, true);
    }

    /** On all fours: spine ridges, splayed spider-like limbs, face turned up at you. */
    private static void crawler(PartDefinition root) {
        CubeListBuilder body = tex(DARK).addBox(-3, -2, -7, 6, 4, 14);
        for (int z = -6; z <= 6; z += 2) body.texOffs(80, 10).addBox(-0.5f, -3.2f, z - 0.5f, 1, 1.2f, 1);
        root.addOrReplaceChild("body", body, PartPose.offsetAndRotation(0, 13, 0, -0.08f, 0, 0));
        limb(root, "arm_l", -3.5f, 12, -6, 1);
        limb(root, "arm_r", 3.5f, 12, -6, -1);
        limb(root, "leg_l", -3.5f, 12, 6, 1);
        limb(root, "leg_r", 3.5f, 12, 6, -1);
        CubeListBuilder head = tex(32, 0).addBox(-1, -1, -4, 2, 2, 4)
                .texOffs(48, 0).addBox(-3.5f, -4, -10.5f, 7, 7, 6);
        grin(head, -10.5f, 1.2f, 3.0f, 0.8f, 8, VOID);
        PartDefinition headPart = root.addOrReplaceChild("head", head, PartPose.offsetAndRotation(0, 12, -7, -0.35f, 0, 0));
        eye(headPart, "eye_l", -1.7f, -2, -10.5f, 1.5f, 1.1f, WHITE, 0.6f, VOID, false);
        eye(headPart, "eye_r", 1.7f, -2, -10.5f, 1.5f, 1.1f, WHITE, 0.6f, VOID, false);
    }

    /** Spider-like limb: upper part splayed outwards, forearm bent down to the ground. */
    private static void limb(PartDefinition root, String name, float x, float y, float z, int side) {
        PartDefinition upper = root.addOrReplaceChild(name, tex(16, 40).addBox(-0.8f, 0, -0.8f, 1.6f, 9, 1.6f),
                PartPose.offsetAndRotation(x, y, z, 0, 0, side * 1.9f));
        PartDefinition lower = upper.addOrReplaceChild("lower", tex(24, 40).addBox(-0.7f, 0, -0.7f, 1.4f, 11, 1.4f),
                PartPose.offsetAndRotation(0, 9, 0, 0, 0, -side * 2.1f));
        fingers(lower.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 11, 0)), DARK, 3, 0.35f);
    }
}
