package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.EventData.CandySpawn;
import com.halloweenevent.data.ScareKind;
import com.halloweenevent.network.AdminAction;
import com.halloweenevent.network.S2CCandySync;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.network.S2CStatus;
import com.halloweenevent.server.AdminActions;
import com.halloweenevent.server.CandyWand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Players an admin spared some scares (the "Exempt" tab), and the candy-spawn stick: right-click a block puts a
 * spawn on top of it, left-click at a candy removes it.
 */
@GameTestHolder(HalloweenEventMod.MODID)
@PrefixGameTestTemplate(false)
public final class ExemptionGameTests {
    private static final String ARENA = "arena";

    private ExemptionGameTests() {}

    private static ServerPlayer op(Arena a, ServerPlayer bot) {
        a.server.getPlayerList().getOps().add(new ServerOpListEntry(bot.getGameProfile(), 4, false));
        return bot;
    }

    private static void deop(Arena a, ServerPlayer bot) {
        a.server.getPlayerList().getOps().remove(bot.getGameProfile());
    }

    /** Spared whisper and text: the ladder skips straight to the figure for them, the others get all of it. */
    @GameTest(template = ARENA, batch = "exemptladder", timeoutTicks = 720)
    public static void anExemptPlayerSkipsTheScaresTheyWereSpared(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 38, 5, 38);
        ServerPlayer admin = op(a, a.bot("ex_admin", 35, 1, 35));
        ServerPlayer spared = a.bot("ex_spared", 10, 1, 10);
        ServerPlayer normal = a.bot("ex_normal", 25, 1, 25);
        a.data.scareLevels.put(spared.getUUID(), 3);
        a.data.scareLevels.put(normal.getUUID(), 3);
        // Typed in a different case: still the same player.
        AdminActions.handle(admin, AdminAction.SET_EXEMPTION, ScareKind.WHISPER.bit() | ScareKind.TEXT.bit(), "EX_SPARED");
        a.assertEquals("ex_spared", a.data.exemptions.get("ex_spared").name(), "the online player's spelling is kept");
        List<S2CStatus> st = a.statuses(spared);
        a.assertEquals(ScareKind.WHISPER.bit() | ScareKind.TEXT.bit(), st.get(st.size() - 1).blocked(), "their client learned the mask");
        AdminActions.handle(admin, AdminAction.SET_EXEMPTION, 0, "bad name!");
        a.assertEquals(1, a.data.exemptions.size(), "a bad name is refused");
        a.start();

        helper.runAtTickTime(660, () -> {
            a.assertEquals(List.of(S2CScare.FIGURE), a.scareTypes(spared), "spared player: no whisper, no text");
            a.assertEquals(List.of(S2CScare.WHISPER, S2CScare.TEXT, S2CScare.FIGURE), a.scareTypes(normal), "everyone else: the whole ladder");
            AdminActions.handle(admin, AdminAction.REMOVE_EXEMPTION, 0, "Ex_Spared");
            helper.assertTrue(a.data.exemptions.isEmpty(), "removed");
            List<S2CStatus> after = a.statuses(spared);
            a.assertEquals(0, after.get(after.size() - 1).blocked(), "their client was told everything is back");
            deop(a, admin);
            a.close();
            helper.succeed();
        });
    }

    /** Pranks honour the switches too: what a player is spared is refused, the rest still lands. */
    @GameTest(template = ARENA, batch = "exemptpranks", timeoutTicks = 60)
    public static void pranksRespectExemptions(GameTestHelper helper) throws CommandSyntaxException {
        Arena a = Arena.open(helper);
        CommandDispatcher<CommandSourceStack> d = a.server.getCommands().getDispatcher();
        CommandSourceStack op = a.server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        ServerPlayer spared = a.bot("exp_spared", 10, 1, 10);
        ServerPlayer other = a.bot("exp_other", 20, 1, 20);
        spared.addTag("exp");
        other.addTag("exp");
        int mask = ScareKind.FACE.bit() | ScareKind.EYES.bit() | ScareKind.GRAB.bit() | ScareKind.FLASHLIGHT.bit();
        a.data.exemptions.put("exp_spared", new com.halloweenevent.data.EventData.Exemption("exp_spared", mask));

        a.assertEquals(0, d.execute("halloween prank face exp_spared", op), "face refused");
        a.assertEquals(0, d.execute("halloween test freecam exp_spared", op), "freecam face is a face too");
        a.assertEquals(0, d.execute("halloween test eyes wall exp_spared", op), "eye wall refused");
        a.assertEquals(0, d.execute("halloween test grab alone exp_spared", op), "grab refused");
        a.assertEquals(0, d.execute("halloween test flashlight exp_spared", op), "flashlight refused");
        a.assertEquals(1, d.execute("halloween test eyes @a[tag=exp]", op), "eyes: only the other one");
        a.assertEquals(2, d.execute("halloween test hypno @a[tag=exp]", op), "hypno was not switched off");
        a.assertEquals(2, d.execute("halloween test clear @a[tag=exp]", op), "clear always works");

        helper.runAtTickTime(20, () -> {
            a.assertEquals(List.of(S2CScare.HYPNO, S2CScare.CLEAR), a.scareTypes(spared), "spared got only what is on");
            a.assertEquals(List.of(S2CScare.EYES, S2CScare.HYPNO, S2CScare.CLEAR), a.scareTypes(other), "the other got everything");
            a.close();
            helper.succeed();
        });
    }

    /**
     * Right-click puts the candy on top of the clicked block (never inside it), in a thin block's own space, and
     * nowhere when something solid is in the way or outside the zone. Holding the stick shows every spawn even
     * with the event stopped; left-click removes the candy under the crosshair, not one behind a wall.
     */
    @GameTest(template = ARENA, batch = "candywand", timeoutTicks = 80)
    public static void theCandyStickPlacesOnTopAndRemovesWhatYouLookAt(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(2, 1, 2, 30, 5, 30);
        ServerPlayer admin = op(a, a.bot("wand_admin", 10, 1, 10));
        ServerPlayer player = a.bot("wand_player", 20, 1, 20);
        helper.assertTrue(CandyWand.is(CandyWand.create()), "the stick is recognised by its NBT");
        helper.assertFalse(CandyWand.is(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK)), "a plain stick is not");
        admin.getInventory().setItem(admin.getInventory().selected, CandyWand.create());

        // A block on the floor: the candy goes above it.
        BlockPos crate = helper.absolutePos(new BlockPos(10, 1, 14));
        a.level.setBlockAndUpdate(crate, Blocks.OAK_PLANKS.defaultBlockState());
        a.level.setBlockAndUpdate(crate.above(), Blocks.AIR.defaultBlockState());
        a.assertEquals(null, CandyWand.place(admin, crate), "placing on the crate");
        a.assertEquals(List.of(crate.above()), a.data.spawns.stream().map(CandySpawn::pos).toList(), "on top of the clicked block");
        a.assertEquals("msg.halloweenevent.admin.duplicate_candy", CandyWand.place(admin, crate), "the same spot twice");
        a.assertEquals(1, a.data.spawns.size(), "the same spot twice is refused");

        // A carpet is floor-thin: the candy goes in its space.
        BlockPos carpet = helper.absolutePos(new BlockPos(6, 1, 10));
        a.level.setBlockAndUpdate(carpet, Blocks.RED_CARPET.defaultBlockState());
        a.level.setBlockAndUpdate(carpet.above(), Blocks.AIR.defaultBlockState());
        a.assertEquals(null, CandyWand.place(admin, carpet), "placing on a carpet");
        a.assertEquals(carpet, a.data.spawns.get(1).pos(), "on a carpet, not a block above it");

        // A block with another one on top: no room.
        BlockPos under = helper.absolutePos(new BlockPos(16, 1, 16));
        a.level.setBlockAndUpdate(under, Blocks.STONE.defaultBlockState());
        a.level.setBlockAndUpdate(under.above(), Blocks.STONE.defaultBlockState());
        a.assertEquals("msg.halloweenevent.wand.no_room", CandyWand.place(admin, under), "a block on top");
        // Outside the zone (the arena floor at x = 35).
        a.assertEquals("msg.halloweenevent.wand.outside_zone", CandyWand.place(admin, helper.absolutePos(new BlockPos(35, 0, 35))), "outside the zone");
        a.assertEquals(2, a.data.spawns.size(), "no room / outside the zone: refused");

        // A non-operator with the stick does nothing.
        player.getInventory().setItem(player.getInventory().selected, CandyWand.create());
        CandyWand.place(player, helper.absolutePos(new BlockPos(20, 0, 22)));
        a.assertEquals(2, a.data.spawns.size(), "non-operators cannot place");

        helper.runAtTickTime(25, () -> {
            List<S2CCandySync> lists = a.candyLists(admin);
            helper.assertFalse(lists.isEmpty(), "holding the stick sent the candy list");
            a.assertEquals(2, lists.get(lists.size() - 1).candies().size(), "the operator sees every spawn with the event stopped");
            List<S2CCandySync> theirs = a.candyLists(player);
            helper.assertTrue(theirs.isEmpty() || theirs.get(theirs.size() - 1).candies().isEmpty(), "a non-operator sees none");

            // Look at the candy on the crate and left-click.
            admin.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(crate.above()).subtract(0, 0.05, 0));
            AdminActions.handle(admin, AdminAction.WAND_REMOVE, 0, "");
            a.assertEquals(1, a.data.spawns.size(), "the looked-at candy is gone");
            helper.assertFalse(a.data.spawns.stream().anyMatch(s -> s.pos().equals(crate.above())), "and it was that one");

            // A wall between the admin and the carpet candy: nothing happens.
            BlockPos wall = helper.absolutePos(new BlockPos(8, 1, 10));
            a.level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            a.level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
            admin.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(carpet));
            AdminActions.handle(admin, AdminAction.WAND_REMOVE, 0, "");
            a.assertEquals(1, a.data.spawns.size(), "not through a wall");

            // Looking at nothing: nothing happens.
            admin.lookAt(EntityAnchorArgument.Anchor.EYES, admin.getEyePosition().add(0, 10, 0));
            AdminActions.handle(admin, AdminAction.WAND_REMOVE, 0, "");
            a.assertEquals(1, a.data.spawns.size(), "looking at the sky removes nothing");

            List<S2CCandySync> after = a.candyLists(admin);
            a.assertEquals(1, after.get(after.size() - 1).candies().size(), "the operator's view was refreshed");
            deop(a, admin);
            a.close();
            helper.succeed();
        });
    }
}
