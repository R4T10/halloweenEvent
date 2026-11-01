package com.halloweenevent.client;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.item.FlashlightItem;
import com.halloweenevent.network.C2SFreecamState;
import com.halloweenevent.network.Net;
import com.halloweenevent.registry.ModItems;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
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
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(HalloweenEventMod.MODID, "flashlight"), DefaultVertexFormat.POSITION),
                        FlashlightRenderer::onShaderLoaded);
            } catch (IOException | RuntimeException e) {
                FlashlightRenderer.shader = null;
                HalloweenEventMod.LOGGER.error("Flashlight shader failed to load; flashlights will give no light", e);
            }
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("candy_hud", HudOverlay::render);
            event.registerAboveAll("horror", ScareEffects::renderOverlay);
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            if (FlashlightClient.DEBUG_KEY_ENABLED) event.register(FlashlightClient.DEBUG_KEY);
        }

        /** Model override {@code halloweenevent:on}: the lens goes dark with the light, flicker included. */
        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> ItemProperties.register(ModItems.FLASHLIGHT.get(),
                    new ResourceLocation(HalloweenEventMod.MODID, "on"), (stack, level, entity, seed) -> {
                        if (!FlashlightItem.isOn(stack)) return 0f;
                        if (!(entity instanceof Player p) || p.level() == null) return 1f;
                        return FlashlightClient.intensity(p, stack, p.level().getGameTime(), 0f) > 0.25f ? 1f : 0f;
                    }));
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
            FlashlightClient.clientTick(mc);
            FlashlightSelfTest.clientTick(mc);
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
                FlashlightRenderer.onLevelRendered(event);
                ZoneFogRenderer.onLevelRendered(event);
            }
        }

        /** With a shader pack the frame is final only now, after Oculus composited it. */
        @SubscribeEvent
        public static void onGuiPre(RenderGuiEvent.Pre event) {
            FlashlightRenderer.renderAfterShaderPack();
            ZoneFogRenderer.renderAfterShaderPack();
        }

        @SubscribeEvent
        public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            ScareEffects.cameraAngles(event);
        }

        /** Last, after every mod changed it: the roll the level is really drawn with (depth passes rebuild the view). */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void recordCameraRoll(ViewportEvent.ComputeCameraAngles event) {
            FlashlightRenderer.cameraRoll = event.getRoll();
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
            FlashlightRenderer.reset();
            FlashlightClient.reset();
            lastFreecam = clientTicks - FREECAM_REPORT_TICKS;
            freecamReported = false;
        }
    }
}
