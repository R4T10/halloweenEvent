package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.server.EventManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Everything that involves two players holding hands. The pairs are formed through Hold Hands' own public
 * calls ({@code HoldManager.onKey}), so the mod's real server-side state is what the event reads.
 */
@GameTestHolder(HalloweenEventMod.MODID)
@PrefixGameTestTemplate(false)
public final class HoldHandsGameTests {
    private static final String ARENA = "arena";

    private HoldHandsGameTests() {}

    @GameTest(template = ARENA, batch = "bothold", timeoutTicks = 60)
    public static void botsCanHoldHandsAndLetGo(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer p = a.bot("hand_a", 20, 1, 20);
        ServerPlayer q = a.bot("hand_b", 21, 1, 20);
        helper.assertFalse(Arena.holding(p) || Arena.holding(q), "nobody holds hands at the start");
        Arena.hold(p, q);
        helper.assertTrue(Arena.holdingEachOther(p, q), "the bots should now hold each other's hand");
        Arena.letGo(p, q);
        helper.assertFalse(Arena.holding(p) || Arena.holding(q), "letting go releases both");
        a.close();
        helper.succeed();
    }

    /**
     * One long scenario with six bots, all inside one zone far from each other:
     * <ul>
     *   <li>alone at level 3: exactly 1 damage per 30 s sequence, never repeated every tick;</li>
     *   <li>alone at level 2 and level 1: no damage;</li>
     *   <li>holding hands the whole time at level 3: no damage;</li>
     *   <li>alone at first, then holding before the figure stage: the timer resets, no damage.</li>
     * </ul>
     */
    @GameTest(template = ARENA, batch = "ladder", timeoutTicks = 1450)
    public static void scareLadderDealsOneDamagePerSequenceAtLevel3AndHandsResetTheTimer(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        ServerPlayer lone3 = a.bot("alone_l3", 5, 1, 5);
        ServerPlayer lone2 = a.bot("alone_l2", 35, 1, 5);
        ServerPlayer lone1 = a.bot("alone_l1", 35, 1, 20);
        ServerPlayer pairA = a.bot("pair_a", 5, 1, 35);
        ServerPlayer pairB = a.bot("pair_b", 6, 1, 35);
        ServerPlayer lateA = a.bot("late_a", 35, 1, 35);
        ServerPlayer lateB = a.bot("late_b", 34, 1, 35);
        for (ServerPlayer p : new ServerPlayer[]{lone3, pairA, pairB, lateA, lateB}) a.data.scareLevels.put(p.getUUID(), 3);
        a.data.scareLevels.put(lone2.getUUID(), 2);
        a.data.scareLevels.put(lone1.getUUID(), 1);
        a.start();
        Arena.hold(pairA, pairB);
        helper.assertTrue(Arena.holdingEachOther(pairA, pairB), "pair holds from the start");

        helper.runAtTickTime(500, () -> {
            Arena.hold(lateA, lateB);     // the figure stage is at about tick 600; this should reset it
            helper.assertTrue(Arena.holdingEachOther(lateA, lateB), "late pair holds");
        });
        helper.runAtTickTime(700, () -> Arena.letGo(lateA, lateB));
        helper.runAtTickTime(660, () -> {
            a.assertHealth(lone3, 19f, "level 3 alone: the first sequence costs exactly 1 health");
            a.assertHealth(lone2, 20f, "level 2 alone: figure but no damage");
            a.assertHealth(lone1, 20f, "level 1 alone: whisper and text but no damage");
            a.assertHealth(pairA, 20f, "holding hands the whole time: nothing happens");
            a.assertHealth(pairB, 20f, "holding hands the whole time: nothing happens");
            a.assertHealth(lateA, 20f, "taking a hand before the figure stage resets the timer");
            a.assertHealth(lateB, 20f, "taking a hand before the figure stage resets the timer");
        });
        helper.runAtTickTime(1100, () -> a.assertHealth(lone3, 19f, "no repeated damage between sequences"));
        helper.runAtTickTime(1280, () -> {
            a.assertHealth(lone3, 18f, "the second sequence costs exactly 1 more health");
            a.assertHealth(lone2, 20f, "level 2 still takes no damage");
            a.assertHealth(pairA, 20f, "the pair is still safe");
            a.assertHealth(lateA, 20f, "released at tick 700, the next sequence is not due yet");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "testgrab", timeoutTicks = 160)
    public static void adminTestGrabDragsTheHeldPlayerAboutTenBlocksAwayEveryTime(GameTestHelper helper) {
        Arena a = Arena.open(helper);       // no event running and no zone: the test must still work
        ServerPlayer victim = a.bot("victim", 20, 1, 20);
        ServerPlayer partner = a.bot("partner", 21, 1, 20);
        Arena.hold(victim, partner);
        helper.assertTrue(Arena.holdingEachOther(victim, partner), "bots hold hands");

        a.assertEquals(null, EventManager.testGrab(victim), "the test grab starts");
        a.assertEquals("cmd.halloweenevent.test_grab_busy", EventManager.testGrab(victim), "a second grab is refused while one runs");
        a.assertEquals("cmd.halloweenevent.test_grab_busy", EventManager.testGrab(partner), "the partner is busy too");

        helper.runAtTickTime(120, () -> {
            helper.assertFalse(Arena.holding(victim) || Arena.holding(partner), "the figure split the pair");
            helper.assertTrue(victim.isAlive(), "the victim survives");
            double d = Math.hypot(victim.getX() - partner.getX(), victim.getZ() - partner.getZ());
            helper.assertTrue(d >= 8.0 && d <= 12.0, "the victim should be about 10 blocks from the partner but was " + d);
            helper.assertTrue(Math.abs(partner.getX() - (helper.absolutePos(new net.minecraft.core.BlockPos(21, 1, 20)).getX() + 0.5)) < 0.01,
                    "the partner must not be moved");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "testgrabno", timeoutTicks = 40)
    public static void adminTestGrabNeedsAHeldHand(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer lonely = a.bot("lonely", 20, 1, 20);
        a.assertEquals("cmd.halloweenevent.test_grab_not_holding", EventManager.testGrab(lonely), "not holding hands");
        a.close();
        helper.succeed();
    }

    /** The real, timed grab: 1 s interval and a 50% roll per check, so it happens within seconds. */
    @GameTest(template = ARENA, batch = "timedgrab", timeoutTicks = 700)
    public static void timedGrabInsideTheZoneSplitsTheCoupleAndKeepsTheVictimInTheZone(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        a.data.grabIntervalSeconds = 1;
        a.data.holdScares = false;
        ServerPlayer p = a.bot("grab_a", 20, 1, 20);
        ServerPlayer q = a.bot("grab_b", 21, 1, 20);
        a.start();
        Arena.hold(p, q);

        helper.succeedWhen(() -> {
            helper.assertFalse(Arena.holding(p) || Arena.holding(q), "waiting for the figure to grab the couple");
            helper.assertTrue(a.data.isInZone(p) && a.data.isInZone(q), "the dragged player must stay inside the zone");
            double d = Math.hypot(p.getX() - q.getX(), p.getZ() - q.getZ());
            helper.assertTrue(d >= 8.0 && d <= 12.0, "the victim ends about 10 blocks from the partner but was " + d);
            a.close();
        });
    }

    @GameTest(template = ARENA, batch = "nograb", timeoutTicks = 260)
    public static void noGrabHappensWhenTheIntervalIsOff(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        a.data.grabIntervalSeconds = 0;
        a.data.holdScares = false;
        ServerPlayer p = a.bot("calm_a", 20, 1, 20);
        ServerPlayer q = a.bot("calm_b", 21, 1, 20);
        a.start();
        Arena.hold(p, q);
        helper.runAtTickTime(240, () -> {
            helper.assertTrue(Arena.holdingEachOther(p, q), "with the grab off the pair stays together");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "partnerleaves", timeoutTicks = 120)
    public static void aPartnerDisconnectingReleasesTheHoldAndTheEventCarriesOn(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        a.start();
        ServerPlayer stays = a.bot("stays", 20, 1, 20);
        ServerPlayer leaves = a.bot("leaves", 21, 1, 20);
        Arena.hold(stays, leaves);
        helper.runAtTickTime(20, () -> a.server.getPlayerList().remove(leaves));
        helper.runAtTickTime(60, () -> {
            helper.assertFalse(Arena.holding(stays), "Hold Hands releases the remaining player");
            helper.assertTrue(a.data.isInZone(stays) && stays.isAlive(), "the remaining player is fine");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "grabquits", timeoutTicks = 120)
    public static void aVictimDisconnectingMidGrabIsHandledSafely(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer victim = a.bot("quitter", 20, 1, 20);
        ServerPlayer partner = a.bot("left_behind", 21, 1, 20);
        Arena.hold(victim, partner);
        a.assertEquals(null, EventManager.testGrab(victim), "grab starts");
        ServerPlayer third = a.bot("newcomer", 22, 1, 20);
        helper.runAtTickTime(8, () -> a.server.getPlayerList().remove(victim));
        helper.runAtTickTime(40, () -> {
            helper.assertFalse(Arena.holding(partner), "the partner is no longer held");
            Arena.hold(partner, third);
            helper.assertTrue(Arena.holdingEachOther(partner, third), "the partner can hold hands with someone new");
        });
        helper.runAtTickTime(60, () -> {
            // A leftover grab record for the vanished victim would make the partner "busy".
            a.assertEquals(null, EventManager.testGrab(third), "no stale grab is left behind");
            a.close();
            helper.succeed();
        });
    }
}
