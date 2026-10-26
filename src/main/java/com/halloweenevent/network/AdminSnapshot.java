package com.halloweenevent.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only view of the event settings sent to an admin's GUI. */
public final class AdminSnapshot {
    public record PlayerRow(UUID id, String name, boolean online, boolean inZone, int count, boolean completed, int level, boolean freecam) {}

    public boolean active;
    @Nullable public BlockPos corner1;
    @Nullable public BlockPos corner2;
    public String dimension = "";
    public int spawnCount;
    public boolean sharedMode;
    public int respawnSeconds;
    public int target;
    public ItemStack reward = ItemStack.EMPTY;
    public String rewardCommand = "";
    public int defaultLevel;
    public boolean holdScares;
    public boolean preventCarry;
    public int carryDropSeconds;
    public boolean freecamGuard;
    public boolean holdHandsLoaded;
    public boolean carryOnLoaded;
    public int grabIntervalSeconds;
    public final List<PlayerRow> players = new ArrayList<>();

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeNullable(corner1, FriendlyByteBuf::writeBlockPos);
        buf.writeNullable(corner2, FriendlyByteBuf::writeBlockPos);
        buf.writeUtf(dimension);
        buf.writeVarInt(spawnCount);
        buf.writeBoolean(sharedMode);
        buf.writeVarInt(respawnSeconds);
        buf.writeVarInt(target);
        buf.writeItem(reward);
        buf.writeUtf(rewardCommand);
        buf.writeVarInt(defaultLevel);
        buf.writeBoolean(holdScares);
        buf.writeBoolean(preventCarry);
        buf.writeVarInt(carryDropSeconds);
        buf.writeBoolean(freecamGuard);
        buf.writeBoolean(holdHandsLoaded);
        buf.writeBoolean(carryOnLoaded);
        buf.writeVarInt(grabIntervalSeconds);
        buf.writeVarInt(players.size());
        for (PlayerRow r : players) {
            buf.writeUUID(r.id());
            buf.writeUtf(r.name(), 64);
            buf.writeBoolean(r.online());
            buf.writeBoolean(r.inZone());
            buf.writeVarInt(r.count());
            buf.writeBoolean(r.completed());
            buf.writeVarInt(r.level());
            buf.writeBoolean(r.freecam());
        }
    }

    public static AdminSnapshot read(FriendlyByteBuf buf) {
        AdminSnapshot s = new AdminSnapshot();
        s.active = buf.readBoolean();
        s.corner1 = buf.readNullable(FriendlyByteBuf::readBlockPos);
        s.corner2 = buf.readNullable(FriendlyByteBuf::readBlockPos);
        s.dimension = buf.readUtf();
        s.spawnCount = buf.readVarInt();
        s.sharedMode = buf.readBoolean();
        s.respawnSeconds = buf.readVarInt();
        s.target = buf.readVarInt();
        s.reward = buf.readItem();
        s.rewardCommand = buf.readUtf();
        s.defaultLevel = buf.readVarInt();
        s.holdScares = buf.readBoolean();
        s.preventCarry = buf.readBoolean();
        s.carryDropSeconds = buf.readVarInt();
        s.freecamGuard = buf.readBoolean();
        s.holdHandsLoaded = buf.readBoolean();
        s.carryOnLoaded = buf.readBoolean();
        s.grabIntervalSeconds = buf.readVarInt();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            s.players.add(new PlayerRow(buf.readUUID(), buf.readUtf(64), buf.readBoolean(), buf.readBoolean(),
                    buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean()));
        }
        return s;
    }
}
