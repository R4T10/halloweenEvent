package com.halloweenevent.data;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All persistent event settings and player progress. Stored in the overworld's data folder. */
public class EventData extends SavedData {
    private static final String NAME = "halloweenevent";

    public enum CandyMode { PER_PLAYER, SHARED }

    public record CandySpawn(int id, BlockPos pos, int variant) {}

    public static final class Progress {
        public String name = "";
        public final IntOpenHashSet collected = new IntOpenHashSet();
        public int count;
        public boolean completed;
    }

    public ResourceKey<Level> dimension = Level.OVERWORLD;
    @Nullable public BlockPos corner1;
    @Nullable public BlockPos corner2;
    public boolean active;
    public int target = 15;
    public final List<CandySpawn> spawns = new ArrayList<>();
    public int nextSpawnId = 1;
    public CandyMode mode = CandyMode.PER_PLAYER;
    public int respawnSeconds = 60;
    public ItemStack reward = new ItemStack(Items.DIAMOND, 3);
    public String rewardCommand = "";
    public int defaultScareLevel = 2;
    public final Map<UUID, Integer> scareLevels = new HashMap<>();
    public boolean holdScares = true;
    public boolean preventCarry = false;
    public int carryDropSeconds = 5;
    public boolean freecamGuard = true;
    /** Seconds between hand-grab checks for each hand-holding pair; 0 disables. */
    public int grabIntervalSeconds = 90;
    public final Map<UUID, Progress> progress = new HashMap<>();

    @Nullable private AABB zoneCache;

    public static EventData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(EventData::load, EventData::new, NAME);
    }

    @Nullable
    public AABB zone() {
        if (corner1 == null || corner2 == null) return null;
        if (zoneCache == null) {
            zoneCache = new AABB(
                    Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()),
                    Math.max(corner1.getX(), corner2.getX()) + 1, Math.max(corner1.getY(), corner2.getY()) + 1, Math.max(corner1.getZ(), corner2.getZ()) + 1);
        }
        return zoneCache;
    }

    public void setCorner(boolean first, @Nullable BlockPos pos) {
        if (first) corner1 = pos; else corner2 = pos;
        zoneCache = null;
        setDirty();
    }

    public boolean isInZone(ServerPlayer player) {
        AABB zone = zone();
        return zone != null && player.level().dimension().equals(dimension) && zone.contains(player.position());
    }

    public int levelFor(UUID id) {
        return scareLevels.getOrDefault(id, defaultScareLevel);
    }

    public Progress progress(ServerPlayer player) {
        Progress p = progress.computeIfAbsent(player.getUUID(), u -> new Progress());
        p.name = player.getGameProfile().getName();
        return p;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString("dimension", dimension.location().toString());
        if (corner1 != null) tag.put("corner1", NbtUtils.writeBlockPos(corner1));
        if (corner2 != null) tag.put("corner2", NbtUtils.writeBlockPos(corner2));
        tag.putBoolean("active", active);
        tag.putInt("target", target);
        tag.putInt("nextSpawnId", nextSpawnId);
        tag.putString("mode", mode.name());
        tag.putInt("respawnSeconds", respawnSeconds);
        tag.put("reward", reward.save(new CompoundTag()));
        tag.putString("rewardCommand", rewardCommand);
        tag.putInt("defaultScareLevel", defaultScareLevel);
        tag.putBoolean("holdScares", holdScares);
        tag.putBoolean("preventCarry", preventCarry);
        tag.putInt("carryDropSeconds", carryDropSeconds);
        tag.putBoolean("freecamGuard", freecamGuard);
        tag.putInt("grabIntervalSeconds", grabIntervalSeconds);

        ListTag spawnList = new ListTag();
        for (CandySpawn s : spawns) {
            CompoundTag c = NbtUtils.writeBlockPos(s.pos());
            c.putInt("id", s.id());
            c.putInt("variant", s.variant());
            spawnList.add(c);
        }
        tag.put("spawns", spawnList);

        CompoundTag levels = new CompoundTag();
        scareLevels.forEach((id, lvl) -> levels.putInt(id.toString(), lvl));
        tag.put("scareLevels", levels);

        CompoundTag players = new CompoundTag();
        progress.forEach((id, p) -> {
            CompoundTag c = new CompoundTag();
            c.putString("name", p.name);
            c.putIntArray("collected", p.collected.toIntArray());
            c.putInt("count", p.count);
            c.putBoolean("completed", p.completed);
            players.put(id.toString(), c);
        });
        tag.put("progress", players);
        return tag;
    }

    private static EventData load(CompoundTag tag) {
        EventData d = new EventData();
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
        if (dim != null) d.dimension = ResourceKey.create(Registries.DIMENSION, dim);
        if (tag.contains("corner1")) d.corner1 = NbtUtils.readBlockPos(tag.getCompound("corner1"));
        if (tag.contains("corner2")) d.corner2 = NbtUtils.readBlockPos(tag.getCompound("corner2"));
        d.active = tag.getBoolean("active");
        d.target = Math.max(1, tag.getInt("target"));
        d.nextSpawnId = Math.max(1, tag.getInt("nextSpawnId"));
        try {
            d.mode = CandyMode.valueOf(tag.getString("mode"));
        } catch (IllegalArgumentException ignored) {
            d.mode = CandyMode.PER_PLAYER;
        }
        d.respawnSeconds = tag.getInt("respawnSeconds");
        d.reward = ItemStack.of(tag.getCompound("reward"));
        d.rewardCommand = tag.getString("rewardCommand");
        d.defaultScareLevel = Math.max(1, Math.min(3, tag.getInt("defaultScareLevel")));
        d.holdScares = tag.getBoolean("holdScares");
        d.preventCarry = tag.getBoolean("preventCarry");
        d.carryDropSeconds = tag.getInt("carryDropSeconds");
        d.freecamGuard = tag.getBoolean("freecamGuard");
        // Added in 1.1: keep defaults for worlds saved by 1.0.
        if (tag.contains("grabIntervalSeconds")) d.grabIntervalSeconds = tag.getInt("grabIntervalSeconds");

        for (Tag t : tag.getList("spawns", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.spawns.add(new CandySpawn(c.getInt("id"), NbtUtils.readBlockPos(c), c.getInt("variant")));
        }
        CompoundTag levels = tag.getCompound("scareLevels");
        for (String key : levels.getAllKeys()) {
            parseUuid(key).ifPresent(id -> d.scareLevels.put(id, levels.getInt(key)));
        }
        CompoundTag players = tag.getCompound("progress");
        for (String key : players.getAllKeys()) {
            parseUuid(key).ifPresent(id -> {
                CompoundTag c = players.getCompound(key);
                Progress p = new Progress();
                p.name = c.getString("name");
                for (int i : c.getIntArray("collected")) p.collected.add(i);
                p.count = c.getInt("count");
                p.completed = c.getBoolean("completed");
                d.progress.put(id, p);
            });
        }
        return d;
    }

    private static java.util.Optional<UUID> parseUuid(String s) {
        try {
            return java.util.Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
