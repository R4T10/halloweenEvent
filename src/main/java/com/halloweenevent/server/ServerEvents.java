package com.halloweenevent.server;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.compat.CarryOnCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = HalloweenEventMod.MODID)
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        EventManager.tick(event.getServer());
        Flashlights.tick(event.getServer());
    }

    // ---- the event flashlight never leaves its owner's inventory ----

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp && Flashlights.onToss(sp, event.getEntity().getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            event.getDrops().removeIf(Flashlights::blocksItemEntity);
        }
    }

    @SubscribeEvent
    public static void onItemEntity(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ItemEntity item && Flashlights.blocksItemEntity(item)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer sp) Flashlights.onContainerClosed(sp, event.getContainer());
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        HalloweenCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) EventManager.syncPlayer(sp);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) EventManager.onLogout(sp);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) EventManager.syncPlayer(sp);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) EventManager.onDeath(sp);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EventManager.onServerStopped();
    }

    /** Carry On picks blocks up in a HIGH-priority listener that skips cancelled events. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!CarryOnCompat.LOADED || !(event.getEntity() instanceof ServerPlayer sp)) return;
        if (EventManager.shouldPreventCarry(sp) && CarryOnCompat.wouldPickUpBlock(sp, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            sp.displayClientMessage(Component.translatable("msg.halloweenevent.carry_denied"), true);
        }
    }
}
