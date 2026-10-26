package com.halloweenevent.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import net.xolt.freecam.Freecam;

/**
 * Free-camera detection on the client.
 * <ul>
 *   <li>Freecam (net.xolt.freecam, supplied JAR): {@code Freecam.isEnabled()} covers free and tripod mode;
 *       {@code Freecam.toggle()} turns whichever is active off through the mod's own code path.</li>
 *   <li>Any other mod that swaps the camera entity ({@code Minecraft#getCameraEntity() != player}):
 *       the camera is forced back with {@code setCameraEntity(player)}.</li>
 * </ul>
 * Mods that move the camera without changing the camera entity (mixins into Camera#setup) cannot be
 * detected by a Forge event; a modified client can bypass any client-side check.
 */
public final class FreecamCompat {
    private static final boolean LOADED = ModList.get().isLoaded("freecam");

    private FreecamCompat() {}

    public static boolean isActive(Minecraft mc) {
        if (mc.player == null) return false;
        if (LOADED && Impl.enabled()) return true;
        return mc.getCameraEntity() != null && mc.getCameraEntity() != mc.player && !mc.player.isSpectator();
    }

    public static void forceOff(Minecraft mc) {
        if (LOADED && Impl.enabled()) Impl.disable();
        if (mc.player != null && mc.getCameraEntity() != mc.player && !mc.player.isSpectator()) {
            mc.setCameraEntity(mc.player);
        }
    }

    private static final class Impl {
        static boolean enabled() {
            return Freecam.isEnabled();
        }

        static void disable() {
            Freecam.toggle();
        }
    }
}
