package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.EventData;
import com.halloweenevent.server.AdminActions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * The event itself: zone, candy collection, completion, rewards, death and re-entry, shared mode.
 * Every test uses bot players that stand where the test puts them (see {@link Arena}); the event updates
 * every 5 ticks, so steps are spaced at least 20 ticks apart.
 */
@GameTestHolder(HalloweenEventMod.MODID)
@PrefixGameTestTemplate(false)
public final class EventGameTests {
    private static final String ARENA = "arena";

    private EventGameTests() {}

    @GameTest(template = ARENA, batch = "candy", timeoutTicks = 260)
    public static void candyIsCollectedOncePerPlayerAndTheRewardIsPaidOnce(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.data.target = 3;
        a.data.reward = new net.minecraft.world.item.ItemStack(Items.DIAMOND, 3);
        a.data.rewardCommand = "give %player% minecraft:apple";
        a.candy(10, 1, 10);
        a.candy(12, 1, 10);
        a.candy(14, 1, 10);
        a.start();
        ServerPlayer p = a.bot("candy_bot", 20, 1, 20);
        ServerPlayer other = a.bot("other_bot", 30, 1, 30);
        ServerPlayer outsider = a.bot("outsider", 38, 1, 38);

        helper.runAtTickTime(15, () -> a.moveTo(p, 10, 1, 10));
        helper.runAtTickTime(40, () -> {
            a.assertEquals(1, a.count(p), "first candy counted");
            a.moveTo(other, 10, 1, 10);
        });
        helper.runAtTickTime(65, () -> {
            a.assertEquals(1, a.count(p), "standing on a collected candy must not count again");
            a.assertEquals(1, a.count(other), "another player can take the same candy in per-player mode");
            a.moveTo(p, 12, 1, 10);
        });
        helper.runAtTickTime(90, () -> {
            a.assertEquals(2, a.count(p), "second candy");
            a.assertEquals(0, a.count(outsider), "a player outside the zone collects nothing");
            a.moveTo(p, 14, 1, 10);
        });
        helper.runAtTickTime(115, () -> {
            a.assertEquals(3, a.count(p), "third candy");
            helper.assertFalse(a.completed(p), "the event is not won before leaving the zone");
            a.assertEquals(0, p.getInventory().countItem(Items.DIAMOND), "no reward before leaving");
            a.moveTo(p, 38, 1, 20);
        });
        helper.runAtTickTime(140, () -> {
            helper.assertTrue(a.completed(p), "walking out with the target reached completes the event");
            a.assertEquals(3, p.getInventory().countItem(Items.DIAMOND), "reward item paid");
            a.assertEquals(1, p.getInventory().countItem(Items.APPLE), "reward command ran once");
            a.assertEquals(0, other.getInventory().countItem(Items.APPLE), "reward command only for the winner");
            a.moveTo(p, 20, 1, 20);
        });
        helper.runAtTickTime(165, () -> a.moveTo(p, 38, 1, 20));
        helper.runAtTickTime(190, () -> {
            a.assertEquals(3, p.getInventory().countItem(Items.DIAMOND), "no second reward after re-entering and leaving");
            a.assertEquals(1, p.getInventory().countItem(Items.APPLE), "reward command must not run twice");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "early", timeoutTicks = 200)
    public static void leavingEarlyKeepsProgressAndGivesNoReward(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.data.target = 3;
        a.candy(10, 1, 10);
        a.candy(12, 1, 10);
        a.candy(14, 1, 10);
        a.start();
        ServerPlayer p = a.bot("early_leaver", 20, 1, 20);

        helper.runAtTickTime(15, () -> a.moveTo(p, 10, 1, 10));
        helper.runAtTickTime(35, () -> a.moveTo(p, 12, 1, 10));
        helper.runAtTickTime(55, () -> {
            a.assertEquals(2, a.count(p), "two candies");
            a.moveTo(p, 38, 1, 20);
        });
        helper.runAtTickTime(80, () -> {
            a.assertEquals(2, a.count(p), "progress is kept after leaving early");
            helper.assertFalse(a.completed(p), "leaving before the target is not a win");
            a.assertEquals(0, p.getInventory().countItem(Items.DIAMOND), "no reward for leaving early");
            a.moveTo(p, 14, 1, 10);
        });
        helper.runAtTickTime(105, () -> {
            a.assertEquals(3, a.count(p), "returning continues the hunt");
            a.moveTo(p, 38, 1, 20);
        });
        helper.runAtTickTime(130, () -> {
            helper.assertTrue(a.completed(p), "completes after reaching the target and leaving");
            a.assertEquals(3, p.getInventory().countItem(Items.DIAMOND), "reward paid");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "death", timeoutTicks = 260)
    public static void dyingInsideNeverCountsAsEscapingButReEnteringWorks(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.data.target = 1;
        a.candy(10, 1, 10);
        a.start();
        ServerPlayer p = a.bot("mortal", 20, 1, 20);
        ServerPlayer[] current = {p};

        helper.runAtTickTime(15, () -> a.moveTo(p, 10, 1, 10));
        helper.runAtTickTime(40, () -> {
            a.assertEquals(1, a.count(p), "candy collected");
            p.hurt(a.level.damageSources().genericKill(), 1000f);
            helper.assertTrue(p.isDeadOrDying(), "the bot should be dead");
        });
        helper.runAtTickTime(65, () -> {
            helper.assertFalse(a.completed(p), "dying with the target reached must not complete the event");
            a.assertEquals(0, p.getInventory().countItem(Items.DIAMOND), "no reward for dying");
            ServerPlayer respawned = a.server.getPlayerList().respawn(p, false);
            Arena.track(respawned);
            current[0] = respawned;
            a.moveTo(respawned, 20, 1, 20);
        });
        helper.runAtTickTime(90, () -> {
            a.assertEquals(1, a.count(current[0]), "progress survives death");
            helper.assertFalse(a.completed(current[0]), "still not completed");
            a.moveTo(current[0], 38, 1, 20);
        });
        helper.runAtTickTime(115, () -> {
            helper.assertTrue(a.completed(current[0]), "walking out after re-entering completes the event");
            a.assertEquals(3, current[0].getInventory().countItem(Items.DIAMOND), "reward paid after re-entering");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "inactive", timeoutTicks = 120)
    public static void nothingHappensWhileTheEventIsStopped(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.data.target = 1;
        a.candy(10, 1, 10);
        ServerPlayer p = a.bot("idle", 10, 1, 10);   // standing on a candy, event never started

        helper.runAtTickTime(60, () -> {
            a.assertEquals(0, a.count(p), "a stopped event must not collect candy");
            a.moveTo(p, 38, 1, 20);
        });
        helper.runAtTickTime(90, () -> {
            helper.assertFalse(a.completed(p), "a stopped event must not complete");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "shared", timeoutTicks = 260)
    public static void sharedCandyIsTakenByTheFirstPlayerAndComesBack(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.data.mode = EventData.CandyMode.SHARED;
        a.data.respawnSeconds = 5;
        a.data.target = 9;
        a.candy(10, 1, 10);
        a.start();
        ServerPlayer first = a.bot("first", 20, 1, 20);
        ServerPlayer second = a.bot("second", 30, 1, 30);

        helper.runAtTickTime(15, () -> a.moveTo(first, 10, 1, 10));
        helper.runAtTickTime(35, () -> {
            a.assertEquals(1, a.count(first), "the first player takes the candy");
            a.moveTo(first, 20, 1, 20);          // step off, or the first player would simply take it again
            a.moveTo(second, 10, 1, 10);
        });
        helper.runAtTickTime(70, () -> a.assertEquals(0, a.count(second), "the candy is gone for the second player while it respawns"));
        helper.runAtTickTime(175, () -> {
            a.assertEquals(1, a.count(second), "the candy comes back after the respawn time and the waiting player takes it");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "zone", timeoutTicks = 60)
    public static void theZoneIsExactlyItsBox(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        ServerPlayer p = a.bot("zone_probe", 20, 1, 20);

        helper.assertTrue(a.data.isInZone(p), "middle of the zone");
        a.moveTo(p, 5, 1, 5);
        helper.assertTrue(a.data.isInZone(p), "the corner block itself is inside");
        a.moveTo(p, 35, 1, 35);
        helper.assertTrue(a.data.isInZone(p), "the opposite corner block is inside");
        a.moveTo(p, 4, 1, 20);
        helper.assertFalse(a.data.isInZone(p), "one block west is outside");
        a.moveTo(p, 36, 1, 20);
        helper.assertFalse(a.data.isInZone(p), "one block east is outside");
        a.moveTo(p, 20, 6, 20);
        helper.assertFalse(a.data.isInZone(p), "above the box is outside");
        a.data.setCorner(true, null);
        helper.assertFalse(a.data.isInZone(p), "no zone means nobody is inside");
        helper.assertTrue(AdminActions.startError(a.data) != null, "an event cannot start without a zone");
        a.close();
        helper.succeed();
    }
}
