package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.item.FlashlightItem;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.registry.ModItems;
import com.halloweenevent.server.Flashlights;
import net.minecraft.world.item.ItemStack;
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

    /**
     * Pranks: every effect fires at the players a selector picks and nobody else, "prank" is the same as "test",
     * leveled scares use each target's own level unless one is given, and the per-player settings (scare level,
     * candy count) and flashlight pranks (flicker, jinx, a lone drag) do what they say.
     */
    @GameTest(template = ARENA, batch = "pranks", timeoutTicks = 140)
    public static void everyPrankReachesTheChosenPlayersOnly(GameTestHelper helper) throws CommandSyntaxException {
        Arena a = Arena.open(helper);
        CommandDispatcher<CommandSourceStack> d = a.server.getCommands().getDispatcher();
        CommandSourceStack op = a.server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        ServerPlayer one = a.bot("pr_one", 10, 1, 10);
        ServerPlayer two = a.bot("pr_two", 20, 1, 20);
        ServerPlayer bystander = a.bot("pr_by", 30, 1, 30);
        one.addTag("pranked");
        two.addTag("pranked");
        a.data.scareLevels.put(one.getUUID(), 1);
        a.data.scareLevels.put(two.getUUID(), 3);

        a.assertEquals(2, d.execute("halloween test whisper @a[tag=pranked]", op), "whisper at both, each at their own level");
        a.assertEquals(2, d.execute("halloween test text 2 @a[tag=pranked]", op), "text at level 2 for both");
        a.assertEquals(1, d.execute("halloween prank face pr_one", op), "\"prank\" is \"test\": face");
        a.assertEquals(1, d.execute("halloween test freecam pr_two", op), "freecam face");
        a.assertEquals(1, d.execute("halloween test eyes wall pr_one", op), "eye wall");
        a.assertEquals(1, d.execute("halloween test hypno pr_two", op), "hypno");
        a.assertEquals(1, d.execute("halloween test chase 3 pr_one", op), "chase at a given level");
        a.assertEquals(2, d.execute("halloween test clear @a[tag=pranked]", op), "clear both screens");
        a.assertEquals(1, d.execute("halloween test figure", op.withEntity(one)), "no target: yourself, at your level");

        a.assertEquals(1, d.execute("halloween fear pr_one 3", op), "set scare level");
        a.assertEquals(3, a.data.levelFor(one.getUUID()), "scare level saved");
        a.assertEquals(1, d.execute("halloween candy pr_two 7", op), "set candy count");
        a.assertEquals(7, a.count(two), "candy count saved");

        // Flashlight pranks (checked right away: with no event running the next sweep takes flashlights away).
        ItemStack light = new ItemStack(ModItems.FLASHLIGHT.get());
        FlashlightItem.switchOn(light, Long.MIN_VALUE, false);
        one.getInventory().offhand.set(0, light);
        a.assertEquals(1, d.execute("halloween test flashlight flicker 5 pr_one", op), "flashlight flicker");
        helper.assertTrue(FlashlightItem.flickerUntil(light) > one.level().getGameTime() + 90, "flickers for 5 s");
        a.assertEquals(1, d.execute("halloween test flashlight jinx pr_one", op), "flashlight jinx");
        helper.assertFalse(FlashlightItem.isOn(light), "jinx kills the light");
        Flashlights.toggle(one, light);
        helper.assertTrue(FlashlightItem.isOn(light) && FlashlightItem.failing(light), "the jinxed switch-on gives up");
        a.assertEquals(0, d.execute("halloween test flashlight jinx pr_by", op), "no flashlight: refused");

        // A lone drag: no partner needed, the victim ends up about 10 blocks away and is then chased.
        double startX = two.getX(), startZ = two.getZ();
        a.assertEquals(1, d.execute("halloween test grab alone pr_two", op), "grab alone");
        a.assertEquals(0, d.execute("halloween test grab pr_two", op), "plain grab still needs a partner");

        helper.runAtTickTime(110, () -> {
            a.assertEquals(List.of(WHISPER, TEXT, S2CScare.FREECAM_FACE, S2CScare.HYPNO, S2CScare.CLEAR, S2CScare.CHASE),
                    a.scareTypes(two), "pr_two got its pranks in order, then the chase after the drag");
            a.assertEquals(List.of(3, 2, 0, 0, 0, 3), a.scares(two).stream().map(S2CScare::level).toList(),
                    "pr_two: whisper at its own level 3, text at the given 2, chase at its level 3");
            a.assertEquals(List.of(WHISPER, TEXT, S2CScare.FACE, S2CScare.EYES, S2CScare.CHASE, S2CScare.CLEAR, FIGURE),
                    a.scareTypes(one), "pr_one got its pranks in order");
            a.assertEquals(List.of(1, 2, 0, 1, 3, 0, 1), a.scares(one).stream().map(S2CScare::level).toList(),
                    "pr_one: whisper at its own level 1, text 2, eye wall 1, chase 3, figure at its level 1");
            a.assertEquals(List.of(), a.scareTypes(bystander), "the bystander received nothing");
            double moved = Math.hypot(two.getX() - startX, two.getZ() - startZ);
            helper.assertTrue(moved > 6, "the lone drag moved pr_two about 10 blocks (moved " + (int) moved + ")");
            a.assertEquals(1, a.grabs(two).size(), "the victim saw the grab once (no partner copy)");
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
