package com.halloweenevent.compat;

import com.halloweenevent.server.EventManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import tschipp.carryon.common.carry.CarryOnDataManager;
import tschipp.carryon.common.carry.PickupHandler;
import tschipp.carryon.common.carry.PlacementHandler;
import tschipp.carryon.events.EntityPickupEvent;

/**
 * Carry On 2.1.2.x integration, verified against the supplied JAR:
 * <ul>
 *   <li>{@code CarryOnDataManager.getCarryData(Player).isCarrying()} - carry state (blocks, entities and players).</li>
 *   <li>{@code EntityPickupEvent} - @Cancelable, posted before Carry On picks up an entity or player.</li>
 *   <li>{@code PickupHandler.canCarryGeneral(ServerPlayer, Vec3)} - Carry On's own pre-check (empty hands, carry key, range, game mode).</li>
 *   <li>{@code PlacementHandler.placeCarried(ServerPlayer)} - the drop routine Carry On itself uses on death.</li>
 * </ul>
 * Block pickups have no dedicated event; Carry On handles them in a HIGH-priority RightClickBlock listener
 * that ignores cancelled events, so cancelling at HIGHEST blocks them (see ServerEvents).
 */
public final class CarryOnCompat {
    public static final boolean LOADED = ModList.get().isLoaded("carryon");

    private CarryOnCompat() {}

    public static void init() {
        if (LOADED) Impl.registerListeners();
    }

    public static boolean isCarrying(ServerPlayer player) {
        return LOADED && Impl.isCarrying(player);
    }

    /** True when Carry On would start a block pickup for this click. */
    public static boolean wouldPickUpBlock(ServerPlayer player, BlockPos pos) {
        return LOADED && Impl.wouldPickUp(player, pos);
    }

    public static void forceDrop(ServerPlayer player) {
        if (LOADED) Impl.forceDrop(player);
    }

    private static final class Impl {
        static void registerListeners() {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityPickupEvent.class, Impl::onEntityPickup);
        }

        static void onEntityPickup(EntityPickupEvent event) {
            if (EventManager.shouldPreventCarry(event.player)) {
                event.setCanceled(true);
                event.player.displayClientMessage(Component.translatable("msg.halloweenevent.carry_denied"), true);
            }
        }

        static boolean isCarrying(ServerPlayer player) {
            return CarryOnDataManager.getCarryData(player).isCarrying();
        }

        static boolean wouldPickUp(ServerPlayer player, BlockPos pos) {
            return PickupHandler.canCarryGeneral(player, Vec3.atCenterOf(pos));
        }

        static void forceDrop(ServerPlayer player) {
            if (CarryOnDataManager.getCarryData(player).isCarrying()) {
                PlacementHandler.placeCarried(player);
            }
        }
    }
}
