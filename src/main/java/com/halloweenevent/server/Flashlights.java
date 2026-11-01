package com.halloweenevent.server;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.item.FlashlightItem;
import com.halloweenevent.registry.ModItems;
import com.halloweenevent.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the flashlight.
 * <ul>
 *   <li>Ownership: exactly one flashlight while inside the active zone, none anywhere else. Checked every 5 ticks
 *   over the whole inventory (main, armour, off hand, crafting grid, the stack on the cursor); it can not be
 *   tossed, dropped on death, or left in a container.</li>
 *   <li>Toggling, and the event killing it. A killed flashlight stays off until switched on again; that switch-on
 *   struggles (stutter and clicks, played by every client from the {@code StartAt} tag) and, at higher scare
 *   levels, may give up and die again.</li>
 * </ul>
 */
public final class Flashlights {
    /** Indexed by scare level 1-3. Random kill chance per 5-tick update inside the zone (alone; doubled holding hands). */
    private static final int[] RANDOM_KILL = {0, 720, 360, 200};
    /** No random kill sooner than this after the previous one. */
    private static final int[] KILL_COOLDOWN = {0, 60 * 20, 40 * 20, 25 * 20};
    /** Chance that a struggling switch-on gives up. */
    private static final float[] FAIL_CHANCE = {0, 0f, 0.15f, 0.35f};

    /** Automated test only: keeps the light steady for screenshots. */
    public static boolean randomKillsOff;

    private static final Map<UUID, Long> LAST_KILL = new HashMap<>();
    /** Struggles that will give up: player -> (StartAt of that struggle, server tick it ends). */
    private static final Map<UUID, long[]> FAILING = new HashMap<>();

    private Flashlights() {}

    // ---- ownership ----

    /**
     * Gives the flashlight to a player inside the zone (off hand if free, so the main hand stays free for holding
     * hands), removes duplicates, keeps its fear level current; takes every one away from anyone else.
     */
    public static void syncInventory(ServerPlayer sp, boolean inside, int fear) {
        if (!inside) {
            strip(sp);
            return;
        }
        Inventory inv = sp.getInventory();
        boolean found = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!FlashlightItem.is(stack)) continue;
            if (found) {
                inv.setItem(i, ItemStack.EMPTY);
                continue;
            }
            found = true;
            if (FlashlightItem.fear(stack) != fear) FlashlightItem.setFear(stack, fear);
        }
        ItemStack carried = sp.containerMenu.getCarried();
        if (FlashlightItem.is(carried)) {
            if (found) {
                sp.containerMenu.setCarried(ItemStack.EMPTY);
                sp.containerMenu.broadcastChanges();
            }
            found = true;
        }
        if (found) return;

        // Always into the off hand (the left hand for right-handed players), so it lights at once and the main hand
        // stays free; whatever was there moves into the inventory.
        ItemStack previous = inv.offhand.get(0);
        if (!previous.isEmpty()) {
            if (!inv.add(previous.copy())) {
                // Full: nothing is dropped or deleted; asked again on the next update (every 5 ticks, message every 3 s).
                if (sp.tickCount % 60 < 5) {
                    sp.displayClientMessage(Component.translatable("msg.halloweenevent.flashlight_full").withStyle(ChatFormatting.RED), true);
                }
                return;
            }
        }
        ItemStack flashlight = new ItemStack(ModItems.FLASHLIGHT.get());
        FlashlightItem.setFear(flashlight, fear);
        inv.offhand.set(0, flashlight);
        sp.displayClientMessage(Component.translatable("msg.halloweenevent.flashlight_given").withStyle(ChatFormatting.GRAY), true);
    }

    /** Removes every flashlight the player carries anywhere. */
    public static void strip(ServerPlayer sp) {
        boolean removed = false;
        Inventory inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (FlashlightItem.is(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
                removed = true;
            }
        }
        if (FlashlightItem.is(sp.containerMenu.getCarried())) {
            sp.containerMenu.setCarried(ItemStack.EMPTY);
            removed = true;
        }
        Container craft = sp.inventoryMenu.getCraftSlots();
        for (int i = 0; i < craft.getContainerSize(); i++) {
            if (FlashlightItem.is(craft.getItem(i))) {
                craft.setItem(i, ItemStack.EMPTY);
                removed = true;
            }
        }
        if (removed) {
            sp.containerMenu.broadcastChanges();
            FAILING.remove(sp.getUUID());
        }
    }

    public static void stripAll(MinecraftServer server) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) strip(sp);
    }

    /** Closing a chest, barrel, ...: a flashlight put into it is gone (it would be a spare outside the zone). */
    public static void onContainerClosed(ServerPlayer sp, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container != sp.getInventory() && FlashlightItem.is(slot.getItem())) slot.set(ItemStack.EMPTY);
        }
    }

    /** Tossed with Q or dragged out of the inventory: straight back into the inventory instead. */
    public static boolean onToss(ServerPlayer sp, ItemStack stack) {
        if (!FlashlightItem.is(stack)) return false;
        sp.getInventory().add(stack.copy());
        return true;
    }

    /** Never in the world as an item: death drops, and any other way (dispensers, other mods). */
    public static boolean blocksItemEntity(ItemEntity entity) {
        return FlashlightItem.is(entity.getItem());
    }

    // ---- light ----

    public static void toggle(ServerPlayer sp, ItemStack stack) {
        if (!FlashlightItem.is(stack)) return;
        String name = sp.getGameProfile().getName();
        if (FlashlightItem.isOn(stack)) {
            FlashlightItem.switchOff(stack, false);
            FAILING.remove(sp.getUUID());
            play(sp, ModSounds.FLASHLIGHT_CLICK.get(), 0.8f, 0.7f);
            HalloweenEventMod.LOGGER.debug("[Flashlight] {} switched off", name);
        } else if (FlashlightItem.isDead(stack)) {
            // Clicks and the final "catch" come from the clients, in step with the stutter they draw.
            long now = sp.level().getGameTime();
            boolean fail = sp.getRandom().nextFloat() < FAIL_CHANCE[FlashlightItem.fear(stack)];
            FlashlightItem.switchOn(stack, now, fail);
            if (fail) FAILING.put(sp.getUUID(), new long[]{now, sp.server.getTickCount() + FlashlightItem.STRUGGLE_TICKS});
            HalloweenEventMod.LOGGER.debug("[Flashlight] {} switched on after a kill: struggling{}", name, fail ? ", will give up" : "");
        } else {
            FlashlightItem.switchOn(stack, Long.MIN_VALUE, false);
            play(sp, ModSounds.FLASHLIGHT_CLICK.get(), 1.0f, 0.7f);
            HalloweenEventMod.LOGGER.debug("[Flashlight] {} switched on", name);
        }
    }

    /** Kills the lit flashlight in the player's hands, if any. @return true if one went out. */
    public static boolean kill(ServerPlayer sp) {
        ItemStack stack = FlashlightItem.heldLit(sp);
        if (stack.isEmpty()) return false;
        FlashlightItem.switchOff(stack, true);
        FAILING.remove(sp.getUUID());
        LAST_KILL.put(sp.getUUID(), (long) sp.server.getTickCount());
        play(sp, ModSounds.FLASHLIGHT_DIE.get(), 1.0f, 0.7f);
        HalloweenEventMod.LOGGER.debug("[Flashlight] {}'s flashlight was killed by the event", sp.getGameProfile().getName());
        return true;
    }

    /** Called every 5 ticks for a player inside the active zone; more often at higher scare levels. */
    public static void randomKill(ServerPlayer sp, boolean holding, long now, int fear) {
        if (randomKillsOff) return;
        Long last = LAST_KILL.get(sp.getUUID());
        if (last != null && now - last < KILL_COOLDOWN[fear]) return;
        if (sp.getRandom().nextInt(RANDOM_KILL[fear] * (holding ? 2 : 1)) != 0) return;
        kill(sp);
    }

    /** Every server tick: struggles that give up die again when their stutter ends. */
    public static void tick(MinecraftServer server) {
        if (FAILING.isEmpty()) return;
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, long[]>> it = FAILING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, long[]> e = it.next();
            if (now < e.getValue()[1]) continue;
            it.remove();
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp == null) continue;
            // Wherever it is now (it may have been moved out of the hand meanwhile).
            Inventory inv = sp.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!FlashlightItem.isOn(stack) || FlashlightItem.startAt(stack) != e.getValue()[0]) continue;
                FlashlightItem.switchOff(stack, true);
                play(sp, ModSounds.FLASHLIGHT_DIE.get(), 1.2f, 0.4f);
            }
        }
    }

    public static void forget(UUID id) {
        LAST_KILL.remove(id);
        FAILING.remove(id);
    }

    private static void play(ServerPlayer sp, SoundEvent sound, float pitch, float volume) {
        sp.level().playSound(null, sp.getX(), sp.getEyeY(), sp.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }
}
