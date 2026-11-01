package com.halloweenevent.item;

import com.halloweenevent.server.Flashlights;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Event flashlight without batteries: handed out on entering the zone and taken away on leaving
 * ({@link Flashlights#syncInventory}). All state lives in the stack's NBT, so held flashlights of other players
 * reach every client through the vanilla equipment sync:
 * <ul>
 *   <li>{@code On}: switched on.</li>
 *   <li>{@code Dead}: the event killed it; the next switch-on struggles first.</li>
 *   <li>{@code StartAt}: game time the struggle began; clients stutter the light for {@link #STRUGGLE_TICKS}.</li>
 *   <li>{@code Fail}: this struggle will not catch; the server switches it off again when it ends.</li>
 *   <li>{@code Fear}: the holder's scare level (1-3); more flicker and dimming the higher it is.</li>
 * </ul>
 * Right-click toggles it; the logic runs on the server.
 */
public class FlashlightItem extends Item {
    /** Length of a struggling switch-on; the client's stutter patterns are exactly this long. */
    public static final int STRUGGLE_TICKS = 45;

    private static final String TAG_ON = "On";
    private static final String TAG_DEAD = "Dead";
    private static final String TAG_START = "StartAt";
    private static final String TAG_FAIL = "Fail";
    private static final String TAG_FEAR = "Fear";

    public FlashlightItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) Flashlights.toggle(sp, stack);
        return InteractionResultHolder.consume(stack);
    }

    /** NBT changes every toggle; only a real slot change should play the re-equip animation. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    public static boolean is(ItemStack stack) {
        return stack.getItem() instanceof FlashlightItem;
    }

    public static boolean isOn(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return is(stack) && tag != null && tag.getBoolean(TAG_ON);
    }

    public static boolean isDead(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_DEAD);
    }

    /** Game time the last struggle began, or {@link Long#MIN_VALUE} if it never struggled. */
    public static long startAt(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_START) ? tag.getLong(TAG_START) : Long.MIN_VALUE;
    }

    public static boolean failing(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_FAIL);
    }

    /** Scare level 1-3 of whoever was handed this flashlight. */
    public static int fear(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(TAG_FEAR) ? Math.max(1, Math.min(3, tag.getInt(TAG_FEAR))) : 1;
    }

    public static void setFear(ItemStack stack, int fear) {
        stack.getOrCreateTag().putInt(TAG_FEAR, fear);
    }

    /**
     * @param struggleFrom game time the struggle starts, or {@link Long#MIN_VALUE} for a plain switch-on
     * @param fail         the struggle gives up at the end (the server switches it off then)
     */
    public static void switchOn(ItemStack stack, long struggleFrom, boolean fail) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(TAG_ON, true);
        tag.remove(TAG_DEAD);
        if (struggleFrom != Long.MIN_VALUE) tag.putLong(TAG_START, struggleFrom);
        else tag.remove(TAG_START);
        if (fail) tag.putBoolean(TAG_FAIL, true);
        else tag.remove(TAG_FAIL);
    }

    /** @param dead true when the event killed it, so the next switch-on struggles. */
    public static void switchOff(ItemStack stack, boolean dead) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(TAG_ON, false);
        tag.remove(TAG_START);
        tag.remove(TAG_FAIL);
        if (dead) tag.putBoolean(TAG_DEAD, true);
    }

    /** The lit flashlight in either hand (main hand first), or {@link ItemStack#EMPTY}. */
    public static ItemStack heldLit(Player player) {
        if (isOn(player.getMainHandItem())) return player.getMainHandItem();
        if (isOn(player.getOffhandItem())) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }
}
