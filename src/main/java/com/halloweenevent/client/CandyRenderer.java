package com.halloweenevent.client;

import com.halloweenevent.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * Candies are not entities or blocks: the client draws floating items at the synced spawn points,
 * so each player only sees the candies they can still collect and the server tracks nothing per tick.
 */
public final class CandyRenderer {
    private static final double RENDER_DIST_SQ = 48 * 48;
    private static final double PARTICLE_DIST_SQ = 16 * 16;
    private static ItemStack[] stacks;

    private CandyRenderer() {}

    private static boolean visibleHere(Minecraft mc) {
        return ClientState.active && !ClientState.completed && !ClientState.CANDIES.isEmpty()
                && mc.level != null && mc.level.dimension().location().equals(ClientState.candyDimension);
    }

    private static ItemStack stack(int variant) {
        if (stacks == null) {
            stacks = ModItems.VARIANTS.stream().map(r -> new ItemStack(r.get())).toArray(ItemStack[]::new);
        }
        return stacks[Math.floorMod(variant, stacks.length)];
    }

    public static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!visibleHere(mc)) return;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float time = mc.level.getGameTime() + event.getPartialTick();

        for (Int2ObjectMap.Entry<ClientState.Candy> e : ClientState.CANDIES.int2ObjectEntrySet()) {
            int id = e.getIntKey();
            if (ClientState.HIDDEN.contains(id)) continue;
            BlockPos p = e.getValue().pos();
            double x = p.getX() + 0.5, y = p.getY() + 0.3, z = p.getZ() + 0.5;
            if (cam.distanceToSqr(x, y, z) > RENDER_DIST_SQ) continue;
            ps.pushPose();
            ps.translate(x - cam.x, y + Mth.sin((time + id * 7) / 10f) * 0.1f - cam.y, z - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees((time * 3 + id * 40) % 360));
            ps.scale(1.5f, 1.5f, 1.5f);
            mc.getItemRenderer().renderStatic(stack(e.getValue().variant()), ItemDisplayContext.GROUND,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ps, buffers, mc.level, id);
            ps.popPose();
        }
        buffers.endBatch();
    }

    /** A few witch particles near nearby candies, twice a second. */
    public static void tickParticles(Minecraft mc, int clientTick) {
        if (clientTick % 10 != 0 || mc.player == null || !visibleHere(mc)) return;
        Vec3 eye = mc.player.getEyePosition();
        for (Int2ObjectMap.Entry<ClientState.Candy> e : ClientState.CANDIES.int2ObjectEntrySet()) {
            if (ClientState.HIDDEN.contains(e.getIntKey())) continue;
            BlockPos p = e.getValue().pos();
            if (eye.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > PARTICLE_DIST_SQ) continue;
            if (mc.level.random.nextFloat() < 0.4f) {
                mc.level.addParticle(ParticleTypes.WITCH, p.getX() + 0.2 + mc.level.random.nextDouble() * 0.6,
                        p.getY() + 0.6, p.getZ() + 0.2 + mc.level.random.nextDouble() * 0.6, 0, 0.02, 0);
            }
        }
    }
}
