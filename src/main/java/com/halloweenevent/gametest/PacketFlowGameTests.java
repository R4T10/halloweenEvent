package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.network.AdminAction;
import com.halloweenevent.network.S2CCandySync;
import com.halloweenevent.network.S2CHandGrab;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.network.S2CStatus;
import com.halloweenevent.server.AdminActions;
import com.halloweenevent.server.EventManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * What the server actually sends to each player. The bots record every packet, so these tests see exactly what
 * real clients would receive: which scares, in which order, at which level, and who is told about what.
 */
@GameTestHolder(HalloweenEventMod.MODID)
@PrefixGameTestTemplate(false)
public final class PacketFlowGameTests {
    private static final String ARENA = "arena";
    private static final int WHISPER = S2CScare.WHISPER, TEXT = S2CScare.TEXT, FIGURE = S2CScare.FIGURE, STOP = S2CScare.STOP;

    private PacketFlowGameTests() {}

    @GameTest(template = ARENA, batch = "flowladder", timeoutTicks = 760)
    public static void theScareLadderSendsWhisperTextFigureInOrderAndStopsWhenHandsAreTaken(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        ServerPlayer lone3 = a.bot("flow_l3", 5, 1, 5);
        ServerPlayer lone1 = a.bot("flow_l1", 35, 1, 5);
        ServerPlayer pairA = a.bot("flow_pa", 5, 1, 35);
        ServerPlayer pairB = a.bot("flow_pb", 6, 1, 35);
        ServerPlayer lateA = a.bot("flow_la", 35, 1, 35);
        ServerPlayer lateB = a.bot("flow_lb", 34, 1, 35);
        a.data.scareLevels.put(lone3.getUUID(), 3);
        a.data.scareLevels.put(lone1.getUUID(), 1);
        for (ServerPlayer p : new ServerPlayer[]{pairA, pairB, lateA, lateB}) a.data.scareLevels.put(p.getUUID(), 2);
        a.start();
        Arena.hold(pairA, pairB);

        helper.runAtTickTime(400, () -> Arena.hold(lateA, lateB));      // after the whisper (5 s) and the text (15 s)
        helper.runAtTickTime(680, () -> {
            a.assertEquals(List.of(WHISPER, TEXT, FIGURE), a.scareTypes(lone3), "alone for 30 s: whisper, text, figure");
            helper.assertTrue(a.scares(lone3).stream().allMatch(s -> s.level() == 3), "the scares carry the player's level (3)");
            a.assertEquals(List.of(WHISPER, TEXT, FIGURE), a.scareTypes(lone1), "level 1 gets the same three stages");
            helper.assertTrue(a.scares(lone1).stream().allMatch(s -> s.level() == 1), "the scares carry the player's level (1)");
            a.assertEquals(List.of(), a.scareTypes(pairA), "holding hands the whole time: no scare packets");
            a.assertEquals(List.of(), a.scareTypes(pairB), "holding hands the whole time: no scare packets");
            a.assertEquals(List.of(WHISPER, TEXT, STOP), a.scareTypes(lateA),
                    "taking a hand after the text stops the scares at once and the figure never comes");
            a.assertEquals(List.of(WHISPER, TEXT, STOP), a.scareTypes(lateB), "the partner is treated the same");

            List<S2CStatus> pair = a.statuses(pairA);
            S2CStatus last = pair.get(pair.size() - 1);
            helper.assertTrue(last.holding(), "the pair is told it is holding hands");
            helper.assertTrue(last.inZone() && last.active(), "and that it is inside an active zone");
            List<S2CStatus> alone = a.statuses(lone3);
            helper.assertFalse(alone.get(alone.size() - 1).holding(), "a lone player is told it is not holding");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "flowgrab", timeoutTicks = 200)
    public static void aGrabTellsBothPlayersAndOnlyTheVictimGetsTheChase(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer victim = a.bot("flow_victim", 20, 1, 20);
        ServerPlayer partner = a.bot("flow_partner", 21, 1, 20);
        a.data.scareLevels.put(victim.getUUID(), 3);
        Arena.hold(victim, partner);
        a.assertEquals(null, EventManager.testGrab(victim), "the grab starts");

        helper.runAtTickTime(150, () -> {
            List<S2CHandGrab> v = a.grabs(victim), p = a.grabs(partner);
            a.assertEquals(1, v.size(), "the victim is shown the figure once");
            a.assertEquals(1, p.size(), "the partner is shown the figure once");
            a.assertEquals(victim.getId(), v.get(0).victimId(), "the packet names the victim");
            a.assertEquals(v.get(0), p.get(0), "both see the same grab");
            helper.assertTrue(Math.abs(Math.hypot(v.get(0).dirX(), v.get(0).dirZ()) - 1.0) < 0.01, "the direction is a unit vector");
            double tx = victim.getX() - partner.getX(), tz = victim.getZ() - partner.getZ();
            helper.assertTrue(v.get(0).dirX() * tx + v.get(0).dirZ() * tz > 0, "the figure drags the victim away from the partner");

            a.assertEquals(List.of(S2CScare.CHASE), a.scareTypes(victim), "the victim alone gets the chase");
            a.assertEquals(3, a.scares(victim).get(0).level(), "the chase carries the victim's level, so only level 3 turns the fog red");
            a.assertEquals(List.of(), a.scareTypes(partner), "the partner gets no chase");
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "flowcandy", timeoutTicks = 200)
    public static void candyAndStatusPacketsReachOnlyTheRightPlayers(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.data.target = 5;
        a.candy(10, 1, 10);
        a.candy(14, 1, 10);
        ServerPlayer taker = a.bot("flow_taker", 20, 1, 20);
        ServerPlayer watcher = a.bot("flow_watcher", 30, 1, 30);
        a.start();

        helper.runAtTickTime(15, () -> {
            for (ServerPlayer p : new ServerPlayer[]{taker, watcher}) {
                List<S2CCandySync> lists = a.candyLists(p);
                S2CCandySync last = lists.get(lists.size() - 1);
                a.assertEquals(2, last.candies().size(), "starting the event sends the candy list to every player");
                helper.assertTrue(last.candies().stream().noneMatch(S2CCandySync.Entry::hidden), "nothing is hidden yet");
            }
            a.moveTo(taker, 10, 1, 10);
        });
        helper.runAtTickTime(40, () -> {
            a.assertEquals(1, a.candyChanges(taker).size(), "the collector is told to hide the candy");
            helper.assertFalse(a.candyChanges(taker).get(0).visible(), "hide, not show");
            a.assertEquals(0, a.candyChanges(watcher).size(), "per-player candy: nobody else is told");
            List<S2CStatus> statuses = a.statuses(taker);
            S2CStatus s = statuses.get(statuses.size() - 1);
            a.assertEquals(1, s.count(), "the collector's status shows 1 candy");
            a.assertEquals(5, s.target(), "and the target");
            EventManager.stop(a.server);
        });
        helper.runAtTickTime(60, () -> {
            for (ServerPlayer p : new ServerPlayer[]{taker, watcher}) {
                List<S2CCandySync> lists = a.candyLists(p);
                a.assertEquals(0, lists.get(lists.size() - 1).candies().size(), "stopping the event clears every candy on every client");
                List<S2CStatus> statuses = a.statuses(p);
                helper.assertFalse(statuses.get(statuses.size() - 1).active(), "and tells them the event is over");
            }
            a.close();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "flowadmin", timeoutTicks = 80)
    public static void adminScreenDataGoesOnlyToOperators(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer rando = a.bot("flow_rando", 5, 1, 5);
        ServerPlayer admin = a.bot("flow_admin", 20, 1, 20);
        a.server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            AdminActions.handle(rando, AdminAction.REFRESH, 0, "");
            AdminActions.handle(rando, AdminAction.SET_TARGET, 7, "");
            a.assertEquals(0, a.adminData(rando).size(), "a non-operator never receives the settings");
            AdminActions.handle(admin, AdminAction.REFRESH, 0, "");
            a.assertEquals(1, a.adminData(admin).size(), "an operator gets a fresh snapshot");
            helper.assertFalse(a.adminData(admin).get(0).open(), "a refresh does not reopen the screen");
            AdminActions.sendSnapshot(admin, true);
            helper.assertTrue(a.adminData(admin).get(1).open(), "/halloween admin opens the screen");
            a.assertEquals(15, a.adminData(admin).get(1).snapshot().target, "the snapshot reflects the real settings");
            a.assertEquals(2, a.adminData(admin).get(1).snapshot().players.size(), "and lists the players");
        } finally {
            a.server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        a.close();
        helper.succeed();
    }
}
