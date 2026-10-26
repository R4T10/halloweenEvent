package com.halloweenevent.server;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.compat.CarryOnCompat;
import com.halloweenevent.compat.HoldHandsCompat;
import com.halloweenevent.data.EventData;
import com.halloweenevent.data.EventData.CandyMode;
import com.halloweenevent.data.EventData.CandySpawn;
import com.halloweenevent.network.AdminAction;
import com.halloweenevent.network.AdminSnapshot;
import com.halloweenevent.network.Net;
import com.halloweenevent.network.S2CAdminData;
import com.halloweenevent.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Validates and applies admin GUI actions. Permission is re-checked on every packet. */
public final class AdminActions {
    private static final int PERMISSION = 2;
    private static final int MAX_SPAWNS = 256;
    private static final int MAX_ROWS = 100;

    private AdminActions() {}

    public static void handle(ServerPlayer sp, int action, int value, String text) {
        if (!sp.hasPermissions(PERMISSION)) {
            HalloweenEventMod.LOGGER.warn("Rejected Halloween admin action {} from non-operator {}", action, sp.getGameProfile().getName());
            return;
        }
        MinecraftServer server = sp.server;
        EventData d = EventData.get(server);
        boolean resync = false;

        switch (action) {
            case AdminAction.REFRESH -> {}
            case AdminAction.START -> {
                String error = startError(d);
                if (error != null) fail(sp, error);
                else if (!d.active) EventManager.start(server);
            }
            case AdminAction.STOP -> {
                if (d.active) EventManager.stop(server);
            }
            case AdminAction.CORNER_1, AdminAction.CORNER_2 -> {
                boolean first = action == AdminAction.CORNER_1;
                if (!sp.level().dimension().equals(d.dimension)) {
                    // Corners and candies must share a dimension: moving to a new one clears the rest.
                    d.dimension = sp.level().dimension();
                    d.setCorner(!first, null);
                    if (!d.spawns.isEmpty()) {
                        d.spawns.clear();
                        info(sp, "msg.halloweenevent.admin.dimension_changed");
                    }
                }
                d.setCorner(first, sp.blockPosition());
                info(sp, "msg.halloweenevent.admin.corner", first ? 1 : 2, sp.blockPosition().toShortString());
                resync = true;
            }
            case AdminAction.CLEAR_ZONE -> {
                d.setCorner(true, null);
                d.setCorner(false, null);
                if (d.active) EventManager.stop(server);
            }
            case AdminAction.RESET_PROGRESS -> {
                EventManager.resetProgress(server);
                info(sp, "msg.halloweenevent.admin.reset");
            }
            case AdminAction.ADD_CANDY -> addCandy(sp, d);
            case AdminAction.REMOVE_NEAREST_CANDY -> {
                CandySpawn nearest = nearest(sp, d);
                if (nearest == null) fail(sp, "msg.halloweenevent.admin.no_candy_near");
                else {
                    d.spawns.remove(nearest);
                    resync = true;
                }
            }
            case AdminAction.CLEAR_CANDIES -> {
                d.spawns.clear();
                resync = true;
            }
            case AdminAction.TOGGLE_MODE -> {
                d.mode = d.mode == CandyMode.PER_PLAYER ? CandyMode.SHARED : CandyMode.PER_PLAYER;
                resync = true;
            }
            case AdminAction.SET_RESPAWN -> d.respawnSeconds = Mth.clamp(value, 5, 600);
            case AdminAction.SET_TARGET -> {
                d.target = Mth.clamp(value, 1, MAX_SPAWNS);
                resync = true;
            }
            case AdminAction.REWARD_FROM_HAND -> {
                ItemStack held = sp.getMainHandItem();
                if (held.isEmpty()) fail(sp, "msg.halloweenevent.admin.hold_item");
                else d.reward = held.copy();
            }
            case AdminAction.SET_REWARD_COMMAND -> {
                String cmd = text.strip();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                d.rewardCommand = cmd.length() > 256 ? cmd.substring(0, 256) : cmd;
            }
            case AdminAction.SET_PLAYER_LEVEL -> {
                try {
                    d.scareLevels.put(UUID.fromString(text), Mth.clamp(value, 1, 3));
                } catch (IllegalArgumentException e) {
                    return;
                }
            }
            case AdminAction.SET_DEFAULT_LEVEL -> d.defaultScareLevel = Mth.clamp(value, 1, 3);
            case AdminAction.TOGGLE_HOLD_SCARES -> d.holdScares = !d.holdScares;
            case AdminAction.TOGGLE_PREVENT_CARRY -> d.preventCarry = !d.preventCarry;
            case AdminAction.SET_CARRY_DROP -> d.carryDropSeconds = Mth.clamp(value, 0, 60);
            case AdminAction.SET_GRAB_INTERVAL -> d.grabIntervalSeconds = Mth.clamp(value, 0, 600);
            case AdminAction.TOGGLE_FREECAM_GUARD -> {
                d.freecamGuard = !d.freecamGuard;
                resync = true;
            }
            default -> {
                return;
            }
        }
        d.setDirty();
        if (resync && d.active) EventManager.syncAll(server);
        sendSnapshot(sp, false);
    }

    @Nullable
    public static String startError(EventData d) {
        if (d.zone() == null) return "msg.halloweenevent.admin.need_zone";
        if (d.spawns.isEmpty()) return "msg.halloweenevent.admin.need_candy";
        return null;
    }

    private static void addCandy(ServerPlayer sp, EventData d) {
        AABB zone = d.zone();
        if (zone == null || !d.isInZone(sp)) {
            fail(sp, "msg.halloweenevent.admin.stand_in_zone");
            return;
        }
        if (d.spawns.size() >= MAX_SPAWNS) {
            fail(sp, "msg.halloweenevent.admin.too_many");
            return;
        }
        BlockPos pos = sp.blockPosition();
        if (d.spawns.stream().anyMatch(s -> s.pos().equals(pos))) {
            fail(sp, "msg.halloweenevent.admin.duplicate_candy");
            return;
        }
        int id = d.nextSpawnId++;
        d.spawns.add(new CandySpawn(id, pos, id % ModItems.VARIANTS.size()));
        info(sp, "msg.halloweenevent.admin.candy_added", d.spawns.size(), pos.toShortString());
        if (d.active) EventManager.syncAll(sp.server);
    }

    @Nullable
    private static CandySpawn nearest(ServerPlayer sp, EventData d) {
        if (!sp.level().dimension().equals(d.dimension)) return null;
        CandySpawn best = null;
        double bestDist = 6 * 6;
        for (CandySpawn s : d.spawns) {
            double dist = s.pos().distToCenterSqr(sp.position());
            if (dist < bestDist) {
                bestDist = dist;
                best = s;
            }
        }
        return best;
    }

    public static void sendSnapshot(ServerPlayer sp, boolean open) {
        EventData d = EventData.get(sp.server);
        AdminSnapshot s = new AdminSnapshot();
        s.active = d.active;
        s.corner1 = d.corner1;
        s.corner2 = d.corner2;
        s.dimension = d.dimension.location().toString();
        s.spawnCount = d.spawns.size();
        s.sharedMode = d.mode == CandyMode.SHARED;
        s.respawnSeconds = d.respawnSeconds;
        s.target = d.target;
        s.reward = d.reward;
        s.rewardCommand = d.rewardCommand;
        s.defaultLevel = d.defaultScareLevel;
        s.holdScares = d.holdScares;
        s.preventCarry = d.preventCarry;
        s.carryDropSeconds = d.carryDropSeconds;
        s.freecamGuard = d.freecamGuard;
        s.holdHandsLoaded = HoldHandsCompat.LOADED;
        s.carryOnLoaded = CarryOnCompat.LOADED;
        s.grabIntervalSeconds = d.grabIntervalSeconds;

        // Online players first, then offline players with saved progress.
        Map<UUID, AdminSnapshot.PlayerRow> rows = new LinkedHashMap<>();
        for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
            EventData.Progress pr = d.progress.get(p.getUUID());
            rows.put(p.getUUID(), new AdminSnapshot.PlayerRow(p.getUUID(), p.getGameProfile().getName(), true,
                    EventManager.isInside(p.getUUID()), pr == null ? 0 : pr.count, pr != null && pr.completed,
                    d.levelFor(p.getUUID()), EventManager.isFreecamFlagged(p.getUUID())));
        }
        for (Map.Entry<UUID, EventData.Progress> e : d.progress.entrySet()) {
            if (rows.size() >= MAX_ROWS) break;
            EventData.Progress pr = e.getValue();
            rows.putIfAbsent(e.getKey(), new AdminSnapshot.PlayerRow(e.getKey(), pr.name, false, false,
                    pr.count, pr.completed, d.levelFor(e.getKey()), false));
        }
        rows.values().stream().limit(MAX_ROWS).forEach(s.players::add);
        Net.toPlayer(sp, new S2CAdminData(s, open));
    }

    private static void info(ServerPlayer sp, String key, Object... args) {
        sp.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GRAY), false);
    }

    private static void fail(ServerPlayer sp, String key) {
        sp.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), false);
    }
}
