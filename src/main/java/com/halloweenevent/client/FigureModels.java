package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
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
 * Skin texture regions: dark skin (0..95), void black (96,32), red (96,64), white (96,96).
 */
public final class FigureModels {
    public enum Kind {
        /** Very tall and thin, arms to the knees, head tilted. */
        TALL(0.85f),
        /** Huge head with an ear-to-ear grin. */
        SMILER(0.8f),
        /** Neck snapped sideways, arms reaching forward, knees bent backwards. */
        BROKEN(0.9f),
        /** On all fours, spine ridges, face turned up at you. */
        CRAWLER(1.0f);

        final float scale;

        Kind(float scale) {
            this.scale = scale;
        }
    }

    private static final ResourceLocation SKIN = new ResourceLocation(HalloweenEventMod.MODID, "textures/entity/figure_skin.png");
    private static final Map<Kind, ModelPart> MODELS = new EnumMap<>(Kind.class);

    private FigureModels() {}

    // ---- rendering ----

    /**
     * @param time    client ticks + partial tick, drives idle animation and twitches
     * @param lunge   true while the figure is jumping at the player
     * @param running sprinting legs (and arms, unless lunging), leaning forward
     * @param lookAt  the point the figure faces (the camera, or the victim it is dragging)
     */
    public static void render(PoseStack ps, MultiBufferSource.BufferSource buffers, Kind kind, Vec3 pos, Vec3 cam,
                              Vec3 lookAt, float time, float alpha, boolean lunge, boolean running) {
        ModelPart root = MODELS.computeIfAbsent(kind, FigureModels::build);
        root.getAllParts().forEach(ModelPart::resetPose);
        animate(kind, root, time, lunge);
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

    private static void animate(Kind kind, ModelPart root, float t, boolean lunge) {
        ModelPart head = root.getChild("head");
        switch (kind) {
            case TALL -> {
                head.zRot += 0.4f + Mth.sin(t * 0.03f) * 0.12f + twitch(t, 1);
                head.xRot += twitch(t, 2) * 0.5f;
                float sway = Mth.sin(t * 0.05f) * 0.07f;
                root.getChild("arm_l").xRot += lunge ? -1.35f : sway;
                root.getChild("arm_r").xRot += lunge ? -1.35f : -sway;
            }
            case SMILER -> {
                head.zRot += Mth.sin(t * 0.02f) * 0.35f + twitch(t, 3);
                head.yRot += twitch(t, 4) * 0.4f;
                if (lunge) {
                    root.getChild("arm_l").xRot -= 1.5f;
                    root.getChild("arm_r").xRot -= 1.5f;
                }
            }
            case BROKEN -> {
                head.zRot += twitch(t, 5) * 0.6f + Mth.sin(t * 0.4f) * 0.04f;
                float tremble = Mth.sin(t * 1.3f) * 0.04f;
                root.getChild("arm_l").xRot += tremble + (lunge ? -0.2f : 0f);
                root.getChild("arm_r").xRot -= tremble + (lunge ? 0.2f : 0f);
            }
            case CRAWLER -> {
                head.yRot += Mth.sin(t * 0.07f) * 0.25f + twitch(t, 6);
                head.zRot += twitch(t, 7);
                float step = Mth.sin(t * 0.35f) * 0.25f;
                root.getChild("arm_l").xRot += step;
                root.getChild("arm_r").xRot -= step;
                root.getChild("leg_l").xRot -= step;
                root.getChild("leg_r").xRot += step;
                if (lunge) head.xRot -= 0.5f;
            }
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
            case TALL -> tall(root);
            case SMILER -> smiler(root);
            case BROKEN -> broken(root);
            case CRAWLER -> crawler(root);
        }
        return LayerDefinition.create(mesh, 128, 128).bakeRoot();
    }

    private static CubeListBuilder skin(int u, int v) {
        return CubeListBuilder.create().texOffs(u, v);
    }

    /** Glowing white eyes on a face whose front is at {@code front} (a -z value). */
    private static void eyes(CubeListBuilder b, float front, float y, float spacing, float w, float h) {
        b.texOffs(96, 96).addBox(-spacing - w / 2, y - h / 2, front - 0.3f, w, h, 0.3f);
        b.texOffs(96, 96).addBox(spacing - w / 2, y - h / 2, front - 0.3f, w, h, 0.3f);
    }

    /** Pinprick pupils in front of the eyes. */
    private static void pupils(CubeListBuilder b, float front, float y, float spacing, float size) {
        b.texOffs(96, 32).addBox(-spacing - size / 2, y - size / 2, front - 0.4f, size, size, 0.1f);
        b.texOffs(96, 32).addBox(spacing - size / 2, y - size / 2, front - 0.4f, size, size, 0.1f);
    }

    /** Curved grin: black mouth segments with white teeth above and below, corners pulled up. */
    private static void grin(CubeListBuilder b, float front, float cy, float halfWidth, float curve, int teeth) {
        float step = 2 * halfWidth / teeth;
        for (int i = 0; i < teeth; i++) {
            float x = -halfWidth + (i + 0.5f) * step;
            float k = x / halfWidth;
            float y = cy - curve * k * k;
            float open = 1.6f - 0.8f * k * k;
            b.texOffs(96, 32).addBox(x - step / 2, y - open / 2, front - 0.2f, step, open, 0.2f);
            b.texOffs(96, 96).addBox(x - step * 0.3f, y - open / 2, front - 0.35f, step * 0.6f, open * 0.45f, 0.15f);
            b.texOffs(96, 96).addBox(x - step * 0.25f, y + open * 0.1f, front - 0.35f, step * 0.5f, open * 0.4f, 0.15f);
        }
    }

    private static void fingers(PartDefinition hand, String prefix, float length, float spread) {
        for (int i = 0; i < 4; i++) {
            float off = (i - 1.5f) * 0.45f;
            hand.addOrReplaceChild(prefix + i, skin(80, 0).addBox(-0.2f, 0, -0.2f, 0.4f, length, 0.4f),
                    PartPose.offsetAndRotation(off, 0, 0, 0.1f, 0, (i - 1.5f) * spread));
        }
    }

    private static void tall(PartDefinition root) {
        root.addOrReplaceChild("leg_l", skin(0, 40).addBox(-1, 0, -1, 2, 30, 2), PartPose.offset(-1.8f, -6, 0));
        root.addOrReplaceChild("leg_r", skin(8, 40).addBox(-1, 0, -1, 2, 30, 2), PartPose.offset(1.8f, -6, 0));
        root.addOrReplaceChild("body", skin(0, 0)
                .addBox(-3.5f, -18, -1.5f, 7, 18, 3)
                .addBox(-0.5f, -17, 1.5f, 1, 15, 1), PartPose.offset(0, -6, 0));
        PartDefinition armL = root.addOrReplaceChild("arm_l", skin(16, 40).addBox(-1, 0, -1, 2, 32, 2),
                PartPose.offsetAndRotation(-4.5f, -23, 0, 0, 0, 0.08f));
        PartDefinition armR = root.addOrReplaceChild("arm_r", skin(24, 40).addBox(-1, 0, -1, 2, 32, 2),
                PartPose.offsetAndRotation(4.5f, -23, 0, 0, 0, -0.08f));
        fingers(armL.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 32, 0)), "f", 7, 0.12f);
        fingers(armR.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 32, 0)), "f", 7, 0.12f);
        CubeListBuilder head = skin(32, 0)
                .addBox(-1, -3, -1, 2, 3, 2)
                .texOffs(48, 0).addBox(-3.5f, -13, -3.5f, 7, 10, 7);
        eyes(head, -3.5f, -9.5f, 1.6f, 1.4f, 0.9f);
        grin(head, -3.5f, -5.5f, 3.0f, 1.6f, 8);
        root.addOrReplaceChild("head", head, PartPose.offset(0, -24, 0));
    }

    private static void smiler(PartDefinition root) {
        root.addOrReplaceChild("leg_l", skin(0, 40).addBox(-1, 0, -1, 2, 18, 2), PartPose.offset(-1.6f, 6, 0));
        root.addOrReplaceChild("leg_r", skin(8, 40).addBox(-1, 0, -1, 2, 18, 2), PartPose.offset(1.6f, 6, 0));
        root.addOrReplaceChild("body", skin(0, 0).addBox(-3, -14, -1.5f, 6, 14, 3), PartPose.offset(0, 6, 0));
        PartDefinition armL = root.addOrReplaceChild("arm_l", skin(16, 40).addBox(-0.75f, 0, -0.75f, 1.5f, 24, 1.5f),
                PartPose.offsetAndRotation(-4, -7, 0, 0, 0, 0.15f));
        PartDefinition armR = root.addOrReplaceChild("arm_r", skin(24, 40).addBox(-0.75f, 0, -0.75f, 1.5f, 24, 1.5f),
                PartPose.offsetAndRotation(4, -7, 0, 0, 0, -0.15f));
        fingers(armL.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 24, 0)), "f", 5, 0.2f);
        fingers(armR.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 24, 0)), "f", 5, 0.2f);
        CubeListBuilder head = skin(32, 0)
                .addBox(-1, -2, -1, 2, 2, 2)
                .texOffs(0, 64).addBox(-6, -14, -5, 12, 12, 10);
        eyes(head, -5, -10.5f, 2.6f, 2.6f, 2.2f);
        pupils(head, -5, -10.5f, 2.6f, 0.8f);
        grin(head, -5, -5.5f, 5.4f, 2.6f, 14);
        root.addOrReplaceChild("head", head, PartPose.offset(0, -8, 0));
    }

    private static void broken(PartDefinition root) {
        PartDefinition thighL = root.addOrReplaceChild("leg_l", skin(0, 40).addBox(-1, 0, -1, 2, 10, 2),
                PartPose.offsetAndRotation(-1.8f, 4, 0, 0.45f, 0, 0));
        PartDefinition thighR = root.addOrReplaceChild("leg_r", skin(8, 40).addBox(-1, 0, -1, 2, 10, 2),
                PartPose.offsetAndRotation(1.8f, 4, 0, 0.45f, 0, 0));
        thighL.addOrReplaceChild("shin", skin(0, 54).addBox(-1, 0, -1, 2, 12, 2), PartPose.offsetAndRotation(0, 10, 0, -0.95f, 0, 0));
        thighR.addOrReplaceChild("shin", skin(8, 54).addBox(-1, 0, -1, 2, 12, 2), PartPose.offsetAndRotation(0, 10, 0, -0.95f, 0, 0));
        root.addOrReplaceChild("body", skin(0, 0).addBox(-3, -14, -1.5f, 6, 14, 3)
                        .addBox(-0.5f, -13, 1.5f, 1, 12, 1),
                PartPose.offsetAndRotation(0, 4, 0, 0.3f, 0, 0));
        PartDefinition armL = root.addOrReplaceChild("arm_l", skin(16, 40).addBox(-0.9f, 0, -0.9f, 1.8f, 22, 1.8f),
                PartPose.offsetAndRotation(-4, -9, -2, -1.5f, 0.15f, 0));
        PartDefinition armR = root.addOrReplaceChild("arm_r", skin(24, 40).addBox(-0.9f, 0, -0.9f, 1.8f, 22, 1.8f),
                PartPose.offsetAndRotation(4, -9, -2, -1.4f, -0.15f, 0));
        fingers(armL.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 22, 0)), "f", 6, 0.25f);
        fingers(armR.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 22, 0)), "f", 6, 0.25f);
        CubeListBuilder head = skin(32, 0)
                .addBox(-0.8f, -6, -0.8f, 1.6f, 6, 1.6f)
                .texOffs(48, 0).addBox(-3.5f, -13, -3.5f, 7, 7, 7);
        eyes(head, -3.5f, -10.5f, 1.6f, 1.2f, 1.2f);
        grin(head, -3.5f, -7.8f, 2.9f, 1.0f, 8);
        root.addOrReplaceChild("head", head, PartPose.offsetAndRotation(0, -10, -1, 0.2f, 0, 1.9f));
    }

    private static void crawler(PartDefinition root) {
        CubeListBuilder body = skin(0, 0).addBox(-3, -2, -7, 6, 4, 14);
        for (int z = -6; z <= 6; z += 2) body.texOffs(80, 10).addBox(-0.5f, -3.2f, z - 0.5f, 1, 1.2f, 1);
        root.addOrReplaceChild("body", body, PartPose.offsetAndRotation(0, 13, 0, -0.08f, 0, 0));
        limb(root, "arm_l", -3.5f, 12, -6, 1);
        limb(root, "arm_r", 3.5f, 12, -6, -1);
        limb(root, "leg_l", -3.5f, 12, 6, 1);
        limb(root, "leg_r", 3.5f, 12, 6, -1);
        CubeListBuilder head = skin(32, 0)
                .addBox(-1, -1, -4, 2, 2, 4)
                .texOffs(48, 0).addBox(-3.5f, -4, -10.5f, 7, 7, 6);
        eyes(head, -10.5f, -2, 1.7f, 1.4f, 1.0f);
        grin(head, -10.5f, 1.2f, 3.0f, 0.8f, 8);
        root.addOrReplaceChild("head", head, PartPose.offsetAndRotation(0, 12, -7, -0.35f, 0, 0));
    }

    /** Spider-like limb: upper part splayed outwards, forearm bent down to the ground. */
    private static void limb(PartDefinition root, String name, float x, float y, float z, int side) {
        PartDefinition upper = root.addOrReplaceChild(name, skin(16, 40).addBox(-0.8f, 0, -0.8f, 1.6f, 9, 1.6f),
                PartPose.offsetAndRotation(x, y, z, 0, 0, side * 1.9f));
        PartDefinition lower = upper.addOrReplaceChild("lower", skin(24, 40).addBox(-0.7f, 0, -0.7f, 1.4f, 11, 1.4f),
                PartPose.offsetAndRotation(0, 9, 0, 0, 0, -side * 2.1f));
        fingers(lower.addOrReplaceChild("hand", CubeListBuilder.create(), PartPose.offset(0, 11, 0)), "f", 3, 0.35f);
    }
}
