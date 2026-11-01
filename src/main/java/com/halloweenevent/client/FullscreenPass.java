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
import org.lwjgl.opengl.GL30;

/**
 * Runs a full-screen shader that reads the main target's colour and depth as textures, without ever sampling
 * the framebuffer it draws into: it draws into a colour-only scratch target, then copies the result back.
 * <p>
 * Sampling the depth attachment of the bound framebuffer is a feedback loop; GPUs with compressed depth then
 * return the cleared value (1.0, "sky") for whole tiles, which showed as hard-edged holes at fixed screen
 * positions in the flashlight and the zone fog.
 */
final class FullscreenPass {
    private static TextureTarget scratch;

    private FullscreenPass() {}

    /** Draws {@code shader} over the whole of {@code main} (its samplers and uniforms already set). */
    static void draw(ShaderInstance shader, RenderTarget main) {
        if (scratch == null) {
            scratch = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
        } else if (scratch.width != main.width || scratch.height != main.height) {
            scratch.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        scratch.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> shader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.vertex(-1, -1, 0).endVertex();
        b.vertex(1, -1, 0).endVertex();
        b.vertex(1, 1, 0).endVertex();
        b.vertex(-1, 1, 0).endVertex();
        BufferUploader.drawWithShader(b.end());

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scratch.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, scratch.width, scratch.height, 0, 0, main.width, main.height,
                GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
        main.bindWrite(true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
    }
}
