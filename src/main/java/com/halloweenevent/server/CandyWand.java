package com.halloweenevent.server;

import com.halloweenevent.data.EventData;
import com.halloweenevent.data.EventData.CandySpawn;
import com.halloweenevent.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The candy-spawn stick: a plain stick marked with NBT, for operators. Right-click a block puts a candy spawn on
 * top of it, left-click at a candy removes it. While an operator holds it they see every spawn, even with the
 * event stopped or the candy already taken.
 */
public final class CandyWand {
    public static final String TAG = "HalloweenCandyWand";
    private static final int PERMISSION = 2;
    private static final double REACH = 6.0;
    /** How far from the look ray a candy may be and still count as hit. */
    private static final double HIT_RADIUS = 0.6;
    /** A block lower than this (carpet, snow layer, grass) is floor: the candy goes in its space, not above it. */
    private static final double FLAT = 0.25;

    private static final Set<UUID> EDITORS = new HashSet<>();

    private CandyWand() {}

    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.getOrCreateTag().putBoolean(TAG, true);
        stack.setHoverName(Component.translatable("item.halloweenevent.candy_wand")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)));
        ListTag lore = new ListTag();
        for (String line : new String[]{"place", "remove", "see"}) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("item.halloweenevent.candy_wand." + line)
                    .withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
        }
        stack.getOrCreateTagElement("display").put("Lore", lore);
        stack.enchant(Enchantments.UNBREAKING, 1);
        stack.getOrCreateTag().putInt("HideFlags", ItemStack.TooltipPart.ENCHANTMENTS.getMask());
        return stack;
    }

    public static boolean is(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return stack.is(Items.STICK) && tag != null && tag.getBoolean(TAG);
    }

    public static boolean holding(Player player) {
        return is(player.getMainHandItem()) || is(player.getOffhandItem());
    }

    /** An operator holding the stick: they get the whole candy list. */
    public static boolean isEditing(ServerPlayer sp) {
        return EDITORS.contains(sp.getUUID());
    }

    public static boolean canUse(Player player) {
        return player.hasPermissions(PERMISSION);
    }

    /** Twice a second: who picked the stick up or put it away gets their candy list resent. */
    public static void tick(MinecraftServer server, int ticks) {
        if (ticks % 10 != 0) return;
        EventData d = null;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            boolean editing = canUse(sp) && holding(sp);
            if (editing == EDITORS.contains(sp.getUUID())) continue;
            if (editing) EDITORS.add(sp.getUUID());
            else EDITORS.remove(sp.getUUID());
            if (d == null) d = EventData.get(server);
            EventManager.sendCandies(sp, d);
        }
    }

    public static void forget(UUID id) {
        EDITORS.remove(id);
    }

    public static void reset() {
        EDITORS.clear();
    }

    // ---- placing ----

    /**
     * Where a right-click on {@code clicked} puts the candy: on top of the block, or in its space when it is
     * floor-thin. Null when something solid is in the way.
     */
    @Nullable
    public static BlockPos placeFor(ServerLevel level, BlockPos clicked) {
        if (height(level, clicked) < FLAT) return clicked;
        BlockPos above = clicked.above();
        return height(level, above) < FLAT ? above : null;
    }

    /** Top of the block's collision shape, 0..1 (0 for air and plants). */
    private static double height(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    /** @return null when placed, otherwise the translation key of why not (also shown to the player) */
    @Nullable
    public static String place(ServerPlayer sp, BlockPos clicked) {
        if (!canUse(sp)) return "commands.help.failed";
        EDITORS.add(sp.getUUID());
        EventData d = EventData.get(sp.server);
        AABB zone = d.zone();
        if (zone == null) {
            return fail(sp, "msg.halloweenevent.admin.need_zone");
        }
        BlockPos pos = placeFor(sp.serverLevel(), clicked);
        if (pos == null) {
            return fail(sp, "msg.halloweenevent.wand.no_room");
        }
        if (!sp.level().dimension().equals(d.dimension) || !zone.contains(Vec3.atCenterOf(pos))) {
            return fail(sp, "msg.halloweenevent.wand.outside_zone");
        }
        if (d.spawns.size() >= AdminActions.MAX_SPAWNS) {
            return fail(sp, "msg.halloweenevent.admin.too_many");
        }
        if (d.spawns.stream().anyMatch(s -> s.pos().equals(pos))) {
            return fail(sp, "msg.halloweenevent.admin.duplicate_candy");
        }
        int id = d.nextSpawnId++;
        d.spawns.add(new CandySpawn(id, pos, id % ModItems.VARIANTS.size()));
        d.setDirty();
        sp.displayClientMessage(Component.translatable("msg.halloweenevent.wand.placed", d.spawns.size(), pos.toShortString())
                .withStyle(ChatFormatting.GREEN), true);
        sp.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.4f);
        resync(sp.server, d);
        return null;
    }

    // ---- removing ----

    /** The candy the player is looking at (nearest along the look ray, not behind a wall), if any. */
    @Nullable
    public static CandySpawn lookedAt(ServerPlayer sp, EventData d) {
        if (!sp.level().dimension().equals(d.dimension)) return null;
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        HitResult wall = sp.level().clip(new ClipContext(eye, eye.add(look.scale(REACH)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, sp));
        double limit = wall.getType() == HitResult.Type.MISS ? REACH : wall.getLocation().distanceTo(eye) + 0.5;
        CandySpawn best = null;
        double bestAlong = Double.MAX_VALUE;
        for (CandySpawn c : d.spawns) {
            Vec3 center = new Vec3(c.pos().getX() + 0.5, c.pos().getY() + 0.45, c.pos().getZ() + 0.5);
            Vec3 to = center.subtract(eye);
            double along = to.dot(look);
            if (along < 0 || along > limit) continue;
            if (to.subtract(look.scale(along)).length() > HIT_RADIUS) continue;
            if (along < bestAlong) {
                bestAlong = along;
                best = c;
            }
        }
        return best;
    }

    public static void remove(ServerPlayer sp) {
        if (!canUse(sp) || !holding(sp)) return;
        EDITORS.add(sp.getUUID());
        EventData d = EventData.get(sp.server);
        CandySpawn hit = lookedAt(sp, d);
        if (hit == null) return;
        d.spawns.remove(hit);
        d.setDirty();
        sp.displayClientMessage(Component.translatable("msg.halloweenevent.wand.removed", hit.pos().toShortString(), d.spawns.size())
                .withStyle(ChatFormatting.YELLOW), true);
        sp.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1f, 0.6f);
        resync(sp.server, d);
    }

    /** Everyone sees the new list while the event runs; otherwise only the operators holding the stick. */
    private static void resync(MinecraftServer server, EventData d) {
        if (d.active) {
            EventManager.syncAll(server);
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isEditing(p)) EventManager.sendCandies(p, d);
        }
    }

    private static String fail(ServerPlayer sp, String key) {
        sp.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
        return key;
    }
}
