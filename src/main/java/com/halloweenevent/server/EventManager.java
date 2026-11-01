package com.halloweenevent.server;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.compat.CarryOnCompat;
import com.halloweenevent.compat.HoldHandsCompat;
import com.halloweenevent.data.EventData;
import com.halloweenevent.data.EventData.CandyMode;
import com.halloweenevent.data.EventData.CandySpawn;
import com.halloweenevent.network.Net;
import com.halloweenevent.network.S2CCandySync;
import com.halloweenevent.network.S2CCandyVisibility;
import com.halloweenevent.network.S2CHandGrab;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.network.S2CStatus;
import com.halloweenevent.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative event logic. Runs every 5 ticks and only does per-player work
 * (an AABB test, a short candy list scan and two map lookups); no entity or block scanning.
 */
public final class EventManager {
    private static final int UPDATE_INTERVAL = 5;
    private static final double PICKUP_RADIUS_SQ = 1.5 * 1.5;
    private static final int WHISPER_TICKS = 5 * 20;
    private static final int TEXT_TICKS = 15 * 20;
    private static final int FIGURE_TICKS = 30 * 20;
    /** Dark-figure drag: reach, then pull for DRAG_TICKS, then the victim-only chase starts. */
    private static final int GRAB_REACH_TICKS = 12;
    private static final int GRAB_DRAG_TICKS = 24;
    private static final int GRAB_CHASE_AT = GRAB_REACH_TICKS + GRAB_DRAG_TICKS + 10;
    private static final double DRAG_DISTANCE = 10.0;

    /** Transient per-player state; rebuilt on login, cleared on logout and on event start/stop. */
    private static final class Session {
        boolean inside;
        long noHoldSince;
        int stage;
        boolean scareShown;
        boolean fogApplied;
        boolean holding;
        boolean carrying;
        long carryStart;
        boolean freecam;
        long grabCheckAt;
    }

    /** A running dark-figure drag, ticked every server tick while it lasts (about 2.5 s). */
    private static final class Grab {
        final UUID victim;
        final UUID partner;
        final Vec3 target;
        final long start;
        int step;

        Grab(UUID victim, UUID partner, Vec3 target, long start) {
            this.victim = victim;
            this.partner = partner;
            this.target = target;
            this.start = start;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** SHARED mode: candy id -> tick at which it becomes visible again. */
    private static final Map<Integer, Long> SHARED_TAKEN = new HashMap<>();
    private static final List<Grab> GRABS = new ArrayList<>();
    private static int ticks;

    private EventManager() {}

    public static void tick(MinecraftServer server) {
        ticks++;
        // Drags need per-tick motion; everything else runs every 5 ticks.
        if (!GRABS.isEmpty()) tickGrabs(server, server.getTickCount());
        if (ticks % UPDATE_INTERVAL != 0) return;
        EventData d = EventData.get(server);
        AABB zone = d.zone();
        if (!d.active || zone == null) {
            // No event: nobody keeps a flashlight (spares from creative, a crash mid-event, ...).
            if (ticks % 20 == 0) Flashlights.stripAll(server);
            return;
        }
        long now = server.getTickCount();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            updatePlayer(sp, d, now);
        }
        if (d.mode == CandyMode.SHARED && ticks % 20 == 0 && !SHARED_TAKEN.isEmpty()) {
            respawnShared(now);
        }
    }

    private static void updatePlayer(ServerPlayer sp, EventData d, long now) {
        Session s = SESSIONS.computeIfAbsent(sp.getUUID(), u -> new Session());
        boolean inside = sp.isAlive() && !sp.isSpectator() && d.isInZone(sp);
        if (inside && !s.inside) {
            enter(sp, d, s, now);
        } else if (!inside && s.inside) {
            exit(sp, d, s, true);
        }
        int fear = Mth.clamp(d.levelFor(sp.getUUID()), 1, 3);
        Flashlights.syncInventory(sp, inside, fear);
        if (!inside) return;

        boolean holding = HoldHandsCompat.isHolding(sp);
        if (holding != s.holding) {
            s.holding = holding;
            sendStatus(sp, d);
        }
        EventData.Progress p = d.progress(sp);
        if (!p.completed) {
            if (p.count < d.target) collectCandies(sp, d, p, now);
            updateHoldScares(sp, d, s, now);
            Flashlights.randomKill(sp, s.holding, now, fear);
        }
        updateCarry(sp, d, s, now);
        updateGrab(sp, d, s, now);
    }

    private static void enter(ServerPlayer sp, EventData d, Session s, long now) {
        s.inside = true;
        s.noHoldSince = now;
        s.stage = 0;
        EventData.Progress p = d.progress(sp);
        if (!p.completed) {
            sp.displayClientMessage(Component.translatable(p.count >= d.target ? "msg.halloweenevent.escape" : "msg.halloweenevent.enter",
                    p.count, d.target).withStyle(ChatFormatting.GOLD), true);
        }
        sendStatus(sp, d);
    }

    /** @param walkedOut false when leaving by death; dying never counts as escaping. */
    private static void exit(ServerPlayer sp, EventData d, Session s, boolean walkedOut) {
        s.inside = false;
        s.holding = false;
        s.grabCheckAt = 0;
        stopScares(sp, s);
        if (s.carrying) {
            s.carrying = false;
            Net.toPlayer(sp, new S2CScare(S2CScare.CARRY_OFF, 0));
        }
        EventData.Progress p = d.progress(sp);
        if (walkedOut && sp.isAlive() && !p.completed && p.count >= d.target) {
            complete(sp, d, p);
        }
        sendStatus(sp, d);
    }

    private static void collectCandies(ServerPlayer sp, EventData d, EventData.Progress p, long now) {
        double px = sp.getX(), py = sp.getY() + 0.9, pz = sp.getZ();
        for (CandySpawn c : d.spawns) {
            double dx = c.pos().getX() + 0.5 - px, dy = c.pos().getY() + 0.5 - py, dz = c.pos().getZ() + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > PICKUP_RADIUS_SQ) continue;
            if (d.mode == CandyMode.PER_PLAYER) {
                if (!p.collected.add(c.id())) continue;
                Net.toPlayer(sp, new S2CCandyVisibility(c.id(), false));
            } else {
                if (SHARED_TAKEN.containsKey(c.id())) continue;
                SHARED_TAKEN.put(c.id(), now + d.respawnSeconds * 20L);
                Net.toAll(new S2CCandyVisibility(c.id(), false));
            }
            p.count++;
            d.setDirty();
            sp.playNotifySound(ModSounds.CANDY_PICKUP.get(), SoundSource.PLAYERS, 0.8f, 0.9f + sp.getRandom().nextFloat() * 0.3f);
            if (p.count >= d.target) {
                sp.sendSystemMessage(Component.translatable("msg.halloweenevent.escape", p.count, d.target).withStyle(ChatFormatting.GOLD));
            }
            sendStatus(sp, d);
            if (p.count >= d.target) break;
        }
    }

    private static void respawnShared(long now) {
        Iterator<Map.Entry<Integer, Long>> it = SHARED_TAKEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (now >= e.getValue()) {
                it.remove();
                Net.toAll(new S2CCandyVisibility(e.getKey(), true));
            }
        }
    }

    private static void updateHoldScares(ServerPlayer sp, EventData d, Session s, long now) {
        if (!d.holdScares || !HoldHandsCompat.LOADED) return;
        if (s.holding) {
            stopScares(sp, s);
            s.noHoldSince = now;
            return;
        }
        long alone = now - s.noHoldSince;
        int level = d.levelFor(sp.getUUID());
        if (s.stage == 0 && alone >= WHISPER_TICKS) {
            s.stage = 1;
            sendScare(sp, s, S2CScare.WHISPER, level);
        } else if (s.stage == 1 && alone >= TEXT_TICKS) {
            s.stage = 2;
            sendScare(sp, s, S2CScare.TEXT, level);
            if (level >= 2) Flashlights.kill(sp);
        } else if (s.stage == 2 && alone >= FIGURE_TICKS) {
            sendScare(sp, s, S2CScare.FIGURE, level);
            Flashlights.kill(sp);
            if (level >= 3) {
                applyFog(sp, s, MobEffects.BLINDNESS, 50);
                sp.hurt(sp.damageSources().magic(), 1.0f);
            }
            // One damage point per sequence; the next sequence restarts the 5/15/30 s ladder.
            s.stage = 0;
            s.noHoldSince = now;
        }
    }

    private static void sendScare(ServerPlayer sp, Session s, int type, int level) {
        s.scareShown = true;
        Net.toPlayer(sp, new S2CScare(type, level));
    }

    /** Level 3 figure: a short vanilla Blindness pulse (hidden icon, no particles); shader packs honour it too. */
    private static void applyFog(ServerPlayer sp, Session s, MobEffect effect, int ticks) {
        s.fogApplied = true;
        sp.addEffect(new MobEffectInstance(effect, ticks, 0, true, false, false));
    }

    private static void stopScares(ServerPlayer sp, Session s) {
        s.stage = 0;
        if (s.fogApplied) {
            s.fogApplied = false;
            sp.removeEffect(MobEffects.DARKNESS);
            sp.removeEffect(MobEffects.BLINDNESS);
        }
        if (s.scareShown) {
            s.scareShown = false;
            Net.toPlayer(sp, new S2CScare(S2CScare.STOP, 0));
        }
    }

    private static void updateCarry(ServerPlayer sp, EventData d, Session s, long now) {
        if (!CarryOnCompat.LOADED) return;
        boolean carrying = CarryOnCompat.isCarrying(sp);
        if (carrying != s.carrying) {
            s.carrying = carrying;
            s.carryStart = now;
            Net.toPlayer(sp, new S2CScare(carrying ? S2CScare.CARRY_ON : S2CScare.CARRY_OFF, 0));
        }
        if (carrying && d.carryDropSeconds > 0 && now - s.carryStart >= d.carryDropSeconds * 20L) {
            CarryOnCompat.forceDrop(sp);
            sp.displayClientMessage(Component.translatable("msg.halloweenevent.carry_dropped"), true);
        }
    }

    private static void complete(ServerPlayer sp, EventData d, EventData.Progress p) {
        p.completed = true;
        d.setDirty();
        give(sp, d.reward.copy());
        if (!d.rewardCommand.isBlank()) {
            MinecraftServer server = sp.server;
            String cmd = d.rewardCommand.replace("%player%", sp.getGameProfile().getName());
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        }
        sp.playNotifySound(ModSounds.EVENT_WIN.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        sp.server.getPlayerList().broadcastSystemMessage(
                Component.translatable("msg.halloweenevent.winner", sp.getDisplayName()).withStyle(ChatFormatting.GOLD), false);
        HalloweenEventMod.LOGGER.info("Halloween event completed by {}", sp.getGameProfile().getName());
    }

    private static void give(ServerPlayer sp, ItemStack stack) {
        if (!stack.isEmpty() && !sp.getInventory().add(stack)) {
            sp.drop(stack, false);
        }
    }

    // ---- dark-figure drag ----

    /** Each hand-holding pair is checked by its smaller-UUID player only. */
    private static void updateGrab(ServerPlayer sp, EventData d, Session s, long now) {
        if (d.grabIntervalSeconds <= 0 || !HoldHandsCompat.LOADED) return;
        ServerPlayer partner = HoldHandsCompat.partner(sp);
        if (partner == null || inGrab(sp.getUUID()) || inGrab(partner.getUUID())) {
            s.grabCheckAt = 0;
            return;
        }
        if (sp.getUUID().compareTo(partner.getUUID()) > 0 || !isInside(partner.getUUID())) return;
        long interval = d.grabIntervalSeconds * 20L;
        if (s.grabCheckAt == 0) {
            s.grabCheckAt = now + interval;
            return;
        }
        if (now < s.grabCheckAt) return;
        s.grabCheckAt = now + interval;
        if (sp.getRandom().nextBoolean()) return;

        boolean first = sp.getRandom().nextBoolean();
        ServerPlayer victim = first ? sp : partner;
        ServerPlayer other = first ? partner : sp;
        Vec3 target = findDragTarget(victim, other, d);
        if (target == null) return;
        startGrab(victim, other, target, now);
    }

    /** @param partner may be {@code null} (admin test without a partner): nothing to release, nobody else notified. */
    private static void startGrab(ServerPlayer victim, ServerPlayer partner, Vec3 target, long now) {
        double dx = target.x - victim.getX(), dz = target.z - victim.getZ();
        double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        GRABS.add(new Grab(victim.getUUID(), partner != null ? partner.getUUID() : victim.getUUID(), target, now));
        Flashlights.kill(victim);
        S2CHandGrab msg = new S2CHandGrab(victim.getId(), (float) (dx / len), (float) (dz / len));
        Net.toPlayer(victim, msg);
        if (partner != null) Net.toPlayer(partner, msg);
    }

    /**
     * Admin test: start a drag right now. Needs an active event with the player inside the zone. Uses their
     * hand-holding partner as the anchor if they have one, otherwise drags them ~10 blocks from where they stand.
     */
    public static boolean testGrab(ServerPlayer victim) {
        EventData d = EventData.get(victim.server);
        if (!d.active || !isInside(victim.getUUID()) || inGrab(victim.getUUID())) return false;
        ServerPlayer partner = HoldHandsCompat.partner(victim);
        Vec3 target = findDragTarget(victim, partner != null ? partner : victim, d);
        if (target == null) return false;
        startGrab(victim, partner, target, victim.server.getTickCount());
        return true;
    }

    /** A random standable spot inside the zone, about {@value #DRAG_DISTANCE} blocks from the partner. */
    private static Vec3 findDragTarget(ServerPlayer victim, ServerPlayer partner, EventData d) {
        AABB zone = d.zone();
        if (zone == null) return null;
        ServerLevel level = victim.serverLevel();
        RandomSource r = victim.getRandom();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = DRAG_DISTANCE - 1 + r.nextDouble() * 2;
            double x = partner.getX() + Math.cos(angle) * dist;
            double z = partner.getZ() + Math.sin(angle) * dist;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(partner.getY()) + 4, Mth.floor(z));
            for (int i = 0; i < 10; i++, m.move(Direction.DOWN)) {
                if (!standable(level, m)) continue;
                Vec3 spot = new Vec3(m.getX() + 0.5, m.getY(), m.getZ() + 0.5);
                if (zone.contains(spot.add(0, 0.5, 0))) return spot;
                break;
            }
        }
        return null;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static boolean inGrab(UUID id) {
        for (Grab g : GRABS) {
            if (g.victim.equals(id) || g.partner.equals(id)) return true;
        }
        return false;
    }

    /**
     * Per tick: the figure grabs at {@link #GRAB_REACH_TICKS} (hands are released), pulls the victim towards the
     * target for {@link #GRAB_DRAG_TICKS}, snaps them there if they got stuck, then starts the chase.
     */
    private static void tickGrabs(MinecraftServer server, long now) {
        Iterator<Grab> it = GRABS.iterator();
        while (it.hasNext()) {
            Grab g = it.next();
            ServerPlayer victim = server.getPlayerList().getPlayer(g.victim);
            if (victim == null || !victim.isAlive() || !isInside(g.victim)) {
                it.remove();
                continue;
            }
            long age = now - g.start;
            if (g.step == 0 && age >= GRAB_REACH_TICKS) {
                ServerPlayer partner = server.getPlayerList().getPlayer(g.partner);
                if (partner != null) HoldHandsCompat.forceRelease(victim, partner);
                victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), ModSounds.GRAB.get(), SoundSource.HOSTILE, 1.0f, 1.0f);
                g.step = 1;
            }
            if (g.step == 1) {
                if (age < GRAB_REACH_TICKS + GRAB_DRAG_TICKS) {
                    pull(victim, g.target);
                } else {
                    if (victim.position().distanceTo(g.target) > 2.0) victim.teleportTo(g.target.x, g.target.y, g.target.z);
                    victim.fallDistance = 0;
                    g.step = 2;
                }
            } else if (g.step == 2 && age >= GRAB_CHASE_AT) {
                Net.toPlayer(victim, new S2CScare(S2CScare.CHASE, 0));
                it.remove();
            }
        }
    }

    private static void pull(ServerPlayer victim, Vec3 target) {
        Vec3 to = target.subtract(victim.position());
        double horiz = Math.sqrt(to.x * to.x + to.z * to.z);
        if (horiz < 0.6) return;
        double speed = Math.min(0.75, horiz * 0.35);
        victim.setDeltaMovement(to.x / horiz * speed, Mth.clamp(to.y * 0.3, -0.3, 0.42), to.z / horiz * speed);
        victim.hurtMarked = true;
        victim.fallDistance = 0;
    }

    // ---- sync ----

    public static void sendStatus(ServerPlayer sp, EventData d) {
        EventData.Progress p = d.progress.get(sp.getUUID());
        Session s = SESSIONS.get(sp.getUUID());
        Net.toPlayer(sp, new S2CStatus(d.active, s != null && s.inside, p != null && p.completed, d.freecamGuard,
                s != null && s.holding,
                p == null ? 0 : p.count, d.target));
    }

    public static void sendCandies(ServerPlayer sp, EventData d) {
        List<S2CCandySync.Entry> list = new ArrayList<>();
        if (d.active) {
            EventData.Progress p = d.progress.get(sp.getUUID());
            for (CandySpawn c : d.spawns) {
                boolean hidden = d.mode == CandyMode.PER_PLAYER
                        ? p != null && p.collected.contains(c.id())
                        : SHARED_TAKEN.containsKey(c.id());
                list.add(new S2CCandySync.Entry(c.id(), c.pos(), c.variant(), hidden));
            }
        }
        Net.toPlayer(sp, new S2CCandySync(d.dimension.location(), list));
    }

    public static void syncPlayer(ServerPlayer sp) {
        EventData d = EventData.get(sp.server);
        sendStatus(sp, d);
        sendCandies(sp, d);
    }

    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) syncPlayer(sp);
    }

    // ---- lifecycle ----

    public static void start(MinecraftServer server) {
        EventData d = EventData.get(server);
        d.active = true;
        d.setDirty();
        SESSIONS.clear();
        SHARED_TAKEN.clear();
        GRABS.clear();
        syncAll(server);
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("msg.halloweenevent.started", d.target).withStyle(ChatFormatting.GOLD), false);
    }

    public static void stop(MinecraftServer server) {
        EventData d = EventData.get(server);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            Session s = SESSIONS.get(sp.getUUID());
            if (s != null && s.inside) {
                stopScares(sp, s);
                Net.toPlayer(sp, new S2CScare(S2CScare.CARRY_OFF, 0));
            }
        }
        d.active = false;
        d.setDirty();
        Flashlights.stripAll(server);
        SESSIONS.clear();
        SHARED_TAKEN.clear();
        GRABS.clear();
        syncAll(server);
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("msg.halloweenevent.stopped").withStyle(ChatFormatting.GRAY), false);
    }

    /** Called on player death: leave the zone without completing. */
    public static void onDeath(ServerPlayer sp) {
        Session s = SESSIONS.get(sp.getUUID());
        if (s != null && s.inside) exit(sp, EventData.get(sp.server), s, false);
    }

    public static void onLogout(ServerPlayer sp) {
        Session s = SESSIONS.remove(sp.getUUID());
        Flashlights.forget(sp.getUUID());
        if (s != null && s.fogApplied) {
            sp.removeEffect(MobEffects.DARKNESS);
            sp.removeEffect(MobEffects.BLINDNESS);
        }
    }

    public static void onServerStopped() {
        SESSIONS.clear();
        SHARED_TAKEN.clear();
        GRABS.clear();
        ticks = 0;
    }

    public static void resetProgress(MinecraftServer server) {
        EventData d = EventData.get(server);
        d.progress.clear();
        d.setDirty();
        SHARED_TAKEN.clear();
        for (Session s : SESSIONS.values()) {
            s.stage = 0;
        }
        syncAll(server);
    }

    // ---- queries ----

    public static boolean shouldPreventCarry(ServerPlayer sp) {
        EventData d = EventData.get(sp.server);
        if (!d.active || !d.preventCarry) return false;
        Session s = SESSIONS.get(sp.getUUID());
        return s != null && s.inside;
    }

    public static boolean isInside(UUID id) {
        Session s = SESSIONS.get(id);
        return s != null && s.inside;
    }

    public static boolean isFreecamFlagged(UUID id) {
        Session s = SESSIONS.get(id);
        return s != null && s.freecam;
    }

    public static void setFreecam(ServerPlayer sp, boolean active) {
        Session s = SESSIONS.get(sp.getUUID());
        if (s == null || s.freecam == active) return;
        s.freecam = active;
        if (active) {
            HalloweenEventMod.LOGGER.info("{} tried to use a free camera inside the Halloween zone", sp.getGameProfile().getName());
        }
    }
}
