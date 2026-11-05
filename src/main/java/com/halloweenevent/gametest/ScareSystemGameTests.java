package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.network.S2CScare;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole jumpscare system as the clients receive it, driven by bot players that hold hands through Hold Hands'
 * own code: every scare type, every stage of the ladder against a hand being taken, the restart after a hand is
 * let go, leaving and re-entering the zone, and each admin test command.
 */
@GameTestHolder(HalloweenEventMod.MODID)
@PrefixGameTestTemplate(false)
public final class ScareSystemGameTests {
    private static final String ARENA = "arena";
    private static final int WHISPER = S2CScare.WHISPER, TEXT = S2CScare.TEXT, FIGURE = S2CScare.FIGURE, STOP = S2CScare.STOP;

    private ScareSystemGameTests() {}

    private static List<Integer> plus(List<Integer> base, int... more) {
        List<Integer> l = new ArrayList<>(base);
        for (int m : more) l.add(m);
        return l;
    }

    /**
     * Four pairs take hands at different moments of the 5 s / 15 s / 30 s ladder (before the whisper, after the
     * whisper, after the text, after the figure), a loner never does. Each pair must get exactly the scares that
     * happened before its hands met, then STOP. Letting go restarts the ladder from the whisper for everybody,
     * with no text and no figure on the first step.
     */
    @GameTest(template = ARENA, batch = "stagehold", timeoutTicks = 860)
    public static void takingAHandAtAnyStageStopsTheScaresAndLettingGoRestartsTheLadder(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        ServerPlayer[][] pairs = {
                {a.bot("st1a", 5, 1, 5), a.bot("st1b", 6, 1, 5)},
                {a.bot("st2a", 5, 1, 12), a.bot("st2b", 6, 1, 12)},
                {a.bot("st3a", 5, 1, 19), a.bot("st3b", 6, 1, 19)},
                {a.bot("st4a", 5, 1, 26), a.bot("st4b", 6, 1, 26)},
        };
        ServerPlayer loner = a.bot("st_loner", 30, 1, 5);
        for (ServerPlayer[] p : pairs) for (ServerPlayer b : p) a.data.scareLevels.put(b.getUUID(), 3);
        a.data.scareLevels.put(loner.getUUID(), 3);
        a.start();

        int[] holdAt = {40, 150, 450, 650};
        for (int i = 0; i < pairs.length; i++) {
            ServerPlayer[] p = pairs[i];
            helper.runAtTickTime(holdAt[i], () -> Arena.hold(p[0], p[1]));
        }

        helper.runAtTickTime(690, () -> {
            List<List<Integer>> expected = List.of(
                    List.of(),
                    List.of(WHISPER, STOP),
                    List.of(WHISPER, TEXT, STOP),
                    List.of(WHISPER, TEXT, FIGURE, STOP));
            String[] when = {"before the whisper", "after the whisper", "after the text", "after the figure"};
            for (int i = 0; i < pairs.length; i++) {
                for (ServerPlayer b : pairs[i]) {
                    a.assertEquals(expected.get(i), a.scareTypes(b), "taking a hand " + when[i]);
                    helper.assertTrue(Arena.holding(b), "still holding " + when[i]);
                }
            }
            a.assertEquals(List.of(WHISPER, TEXT, FIGURE), a.scareTypes(loner), "the loner got the whole ladder and no STOP");
            a.assertHealth(loner, 19f, "level 3 loner: one damage for the figure");
            a.assertHealth(pairs[3][0], 19f, "the figure had already struck pair 4 before its hands met");
            a.assertHealth(pairs[3][1], 19f, "and its partner");
            for (int i = 0; i < 3; i++) for (ServerPlayer b : pairs[i]) a.assertHealth(b, 20f, "pair " + (i + 1) + " is unhurt");
            for (int i = 0; i < pairs.length; i++) Arena.letGo(pairs[i][0], pairs[i][1]);
        });

        helper.runAtTickTime(830, () -> {
            List<List<Integer>> before = List.of(
                    List.of(),
                    List.of(WHISPER, STOP),
                    List.of(WHISPER, TEXT, STOP),
                    List.of(WHISPER, TEXT, FIGURE, STOP));
            for (int i = 0; i < pairs.length; i++) {
                for (ServerPlayer b : pairs[i]) {
                    helper.assertFalse(Arena.holding(b), "pair " + (i + 1) + " let go");
                    a.assertEquals(plus(before.get(i), WHISPER), a.scareTypes(b),
                            "after letting go the ladder starts again with the whisper only (pair " + (i + 1) + ")");
                }
            }
            a.close();
            helper.succeed();
        });
    }

    /** Alone for the whisper and the text, then out of the zone: nothing more is sent while outside; back in, it starts over. */
    @GameTest(template = ARENA, batch = "zoneexit", timeoutTicks = 1100)
    public static void leavingTheZoneEndsTheScaresAndReEnteringStartsAFreshLadder(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        ServerPlayer p = a.bot("zx_player", 20, 1, 20);
        a.data.scareLevels.put(p.getUUID(), 3);
        a.start();

        int[] atExit = new int[1];
        helper.runAtTickTime(400, () -> {
            a.assertEquals(List.of(WHISPER, TEXT), a.scareTypes(p), "inside: whisper and text so far");
            a.moveTo(p, 40, 1, 20);                      // x 40 is outside the zone (2..38)
        });
        helper.runAtTickTime(430, () -> atExit[0] = a.scareTypes(p).size());
        helper.runAtTickTime(900, () -> {
            List<Integer> types = a.scareTypes(p);
            a.assertEquals(atExit[0], types.size(), "nothing more is sent in 470 ticks outside the zone: no figure, no damage");
            helper.assertFalse(types.contains(FIGURE), "no figure outside the zone");
            a.assertHealth(p, 20f, "no damage outside the zone");
            a.moveTo(p, 20, 1, 20);
        });
        helper.runAtTickTime(1040, () -> {
            List<Integer> types = a.scareTypes(p);
            a.assertEquals(WHISPER, types.get(types.size() - 1), "re-entering starts the ladder from the whisper");
            a.assertEquals(atExit[0] + 1, types.size(), "and only the whisper has come so far");
            a.close();
            helper.succeed();
        });
    }

    /** Every admin test command reaches its target with the right type and level, and nobody else. */
    @GameTest(template = ARENA, batch = "scarecmds", timeoutTicks = 60)
    public static void everyScareTestCommandSendsItsPacketToTheTargetOnly(GameTestHelper helper) throws CommandSyntaxException {
        Arena a = Arena.open(helper);
        CommandDispatcher<CommandSourceStack> d = a.server.getCommands().getDispatcher();
        CommandSourceStack op = a.server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        ServerPlayer target = a.bot("sc_target", 20, 1, 20);
        ServerPlayer bystander = a.bot("sc_bystander", 22, 1, 20);
        a.data.scareLevels.put(target.getUUID(), 2);

        a.assertEquals(1, d.execute("halloween test figure 1 sc_target", op), "figure 1");
        a.assertEquals(1, d.execute("halloween test figure 2 sc_target", op), "figure 2");
        a.assertEquals(1, d.execute("halloween test figure 3 sc_target", op), "figure 3");
        a.assertEquals(1, d.execute("halloween test eyes", op.withEntity(target)), "eyes");
        a.assertEquals(1, d.execute("halloween test eyes wall", op.withEntity(target)), "eyes wall");
        a.assertEquals(1, d.execute("halloween test hypno", op.withEntity(target)), "hypno");
        a.assertEquals(1, d.execute("halloween test chase sc_target", op), "chase");

        helper.runAtTickTime(5, () -> {
            List<S2CScare> got = a.scares(target);
            a.assertEquals(List.of(FIGURE, FIGURE, FIGURE, S2CScare.EYES, S2CScare.EYES, S2CScare.HYPNO, S2CScare.CHASE),
                    a.scareTypes(target), "the target received every test scare, in order");
            a.assertEquals(List.of(1, 2, 3, 0, 1, 0, 2), got.stream().map(S2CScare::level).toList(),
                    "figure levels 1-3, eyes swarm 0, eyes wall 1, hypno 0, chase carries the player's own level (2)");
            a.assertEquals(List.of(), a.scareTypes(bystander), "the bystander received nothing");
            a.close();
            helper.succeed();
        });
    }

    /** The chase tints the fog by the victim's level: a grab sends 1, 2 or 3 as the victim has it. */
    @GameTest(template = ARENA, batch = "chaselevels", timeoutTicks = 200)
    public static void theChaseCarriesTheVictimsLevelAtEveryLevel(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer[] victims = new ServerPlayer[3];
        for (int level = 1; level <= 3; level++) {
            ServerPlayer v = a.bot("cl_v" + level, 6, 1, 6 + level * 10);
            ServerPlayer partner = a.bot("cl_p" + level, 7, 1, 6 + level * 10);
            a.data.scareLevels.put(v.getUUID(), level);
            Arena.hold(v, partner);
            a.assertEquals(null, com.halloweenevent.server.EventManager.testGrab(v), "grab starts for level " + level);
            victims[level - 1] = v;
        }
        helper.runAtTickTime(150, () -> {
            for (int level = 1; level <= 3; level++) {
                ServerPlayer v = victims[level - 1];
                a.assertEquals(List.of(S2CScare.CHASE), a.scareTypes(v), "level " + level + " victim gets one chase");
                a.assertEquals(level, a.scares(v).get(0).level(), "the chase carries level " + level);
            }
            a.close();
            helper.succeed();
        });
    }
}
