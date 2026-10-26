package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.network.C2SFreecamState;
import com.halloweenevent.network.Net;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

public final class ClientEvents {
    private ClientEvents() {}

    @Mod.EventBusSubscriber(modid = HalloweenEventMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        /** A broken driver/shader only falls back to vanilla fog; it must never stop the game loading. */
        @SubscribeEvent
        public static void registerShaders(RegisterShadersEvent event) {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(HalloweenEventMod.MODID, "zone_fog"), DefaultVertexFormat.POSITION),
                        s -> ZoneFogRenderer.shader = s);
            } catch (IOException | RuntimeException e) {
                ZoneFogRenderer.shader = null;
                HalloweenEventMod.LOGGER.error("Zone fog shader failed to load; using vanilla fog instead", e);
            }
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("candy_hud", HudOverlay::render);
            event.registerAboveAll("horror", ScareEffects::renderOverlay);
        }
    }

    @Mod.EventBusSubscriber(modid = HalloweenEventMod.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        /** Report "free camera seen" for this long after the last detection to avoid packet flapping. */
        private static final int FREECAM_REPORT_TICKS = 100;
        private static int clientTicks;
        private static int lastFreecam = -FREECAM_REPORT_TICKS;
        private static boolean freecamReported;

        private ForgeBus() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            clientTicks++;
            ScareEffects.clientTick(mc);
            ZoneFogRenderer.clientTick();
            CandyRenderer.tickParticles(mc, clientTicks);
            guardFreecam(mc);
        }

        private static void guardFreecam(Minecraft mc) {
            if (ClientState.active && ClientState.inZone && ClientState.freecamGuard && FreecamCompat.isActive(mc)) {
                FreecamCompat.forceOff(mc);
                ScareEffects.freecamCaught();
                lastFreecam = clientTicks;
            }
            boolean flagged = clientTicks - lastFreecam < FREECAM_REPORT_TICKS;
            if (flagged != freecamReported) {
                freecamReported = flagged;
                Net.toServer(new C2SFreecamState(flagged));
            }
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                CandyRenderer.render(event);
                ScareEffects.renderFigure(event);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                ZoneFogRenderer.onLevelRendered(event);
            }
        }

        /** With a shader pack the frame is final only now, after Oculus composited it. */
        @SubscribeEvent
        public static void onGuiPre(RenderGuiEvent.Pre event) {
            ZoneFogRenderer.renderAfterShaderPack();
        }

        @SubscribeEvent
        public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            ScareEffects.cameraAngles(event);
        }

        @SubscribeEvent
        public static void onRenderFog(ViewportEvent.RenderFog event) {
            ScareEffects.renderFog(event);
        }

        @SubscribeEvent
        public static void onFogColor(ViewportEvent.ComputeFogColor event) {
            ScareEffects.fogColor(event);
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientState.reset();
            ScareEffects.stopAll();
            ZoneFogRenderer.reset();
            lastFreecam = clientTicks - FREECAM_REPORT_TICKS;
            freecamReported = false;
        }
    }
}
