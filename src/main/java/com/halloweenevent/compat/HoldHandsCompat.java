package com.halloweenevent.compat;

import com.seaz.holdhands.HoldManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * Hold Hands 1.1.4 integration. Uses the mod's public server-side state:
 * {@code HoldManager.isHolding(UUID)} (an entry in its HELD map) and
 * {@code HoldManager.partnerOf(ServerPlayer)} (resolves the online partner). Server thread only.
 */
public final class HoldHandsCompat {
    public static final boolean LOADED = ModList.get().isLoaded("holdhands");

    private HoldHandsCompat() {}

    public static boolean isHolding(ServerPlayer player) {
        return LOADED && Impl.isHolding(player);
    }

    @Nullable
    public static ServerPlayer partner(ServerPlayer player) {
        return LOADED ? Impl.partner(player) : null;
    }

    /**
     * Breaks the pair through Hold Hands' own {@code release(server, player, Reason.SLIPPED, partner)}:
     * removes both HELD entries, restores lent flight and syncs both clients.
     */
    public static void forceRelease(ServerPlayer player, ServerPlayer partner) {
        if (LOADED) Impl.release(player, partner);
    }

    /** Separate class so Hold Hands classes are only resolved when the mod is present. */
    private static final class Impl {
        static boolean isHolding(ServerPlayer player) {
            return HoldManager.isHolding(player.getUUID()) && HoldManager.partnerOf(player) != null;
        }

        static ServerPlayer partner(ServerPlayer player) {
            return HoldManager.isHolding(player.getUUID()) ? HoldManager.partnerOf(player) : null;
        }

        static void release(ServerPlayer player, ServerPlayer partner) {
            if (HoldManager.isHolding(player.getUUID())) {
                HoldManager.release(player.server, player, HoldManager.Reason.SLIPPED, partner);
            }
        }
    }
}
