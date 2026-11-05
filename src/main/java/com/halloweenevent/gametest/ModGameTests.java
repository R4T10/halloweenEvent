package com.halloweenevent.gametest;

import com.halloweenevent.HalloweenEventMod;
import com.halloweenevent.data.EventData;
import com.halloweenevent.network.AdminAction;
import com.halloweenevent.network.AdminSnapshot;
import com.halloweenevent.network.C2SAdminAction;
import com.halloweenevent.network.C2SFreecamState;
import com.halloweenevent.network.S2CAdminData;
import com.halloweenevent.network.S2CCandySync;
import com.halloweenevent.network.S2CCandyVisibility;
import com.halloweenevent.network.S2CHandGrab;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.network.S2CStatus;
import com.halloweenevent.registry.ModItems;
import com.halloweenevent.registry.ModSounds;
import com.halloweenevent.server.AdminActions;
import com.halloweenevent.server.EventManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Saving, network packets, admin permissions, commands and registrations. */
@GameTestHolder(HalloweenEventMod.MODID)
@PrefixGameTestTemplate(false)
public final class ModGameTests {
    private static final String ARENA = "arena";

    private ModGameTests() {}

    // ---- registrations ----

    @GameTest(template = ARENA, batch = "registry", timeoutTicks = 40)
    public static void everyRegisteredItemAndSoundIsKnownToTheGame(GameTestHelper helper) {
        for (var item : ModItems.VARIANTS) {
            helper.assertTrue(item.isPresent() && ForgeRegistries.ITEMS.containsValue(item.get()), "candy item registered: " + item.getId());
        }
        for (var sound : List.of(ModSounds.WHISPER, ModSounds.WHISPER_LOUD, ModSounds.STINGER, ModSounds.SCREAM, ModSounds.GLITCH,
                ModSounds.HEARTBEAT, ModSounds.CANDY_PICKUP, ModSounds.EVENT_WIN, ModSounds.FOOTSTEP, ModSounds.GRAB)) {
            helper.assertTrue(sound.isPresent() && ForgeRegistries.SOUND_EVENTS.containsValue(sound.get()), "sound registered: " + sound.getId());
        }
        helper.succeed();
    }

    // ---- saving ----

    @GameTest(template = ARENA, batch = "save", timeoutTicks = 40)
    public static void eventDataSurvivesSavingAndLoading(GameTestHelper helper) {
        EventData d = new EventData();
        d.dimension = Level.NETHER;
        d.setCorner(true, new BlockPos(-5, 60, 12));
        d.setCorner(false, new BlockPos(40, 80, -33));
        d.active = true;
        d.target = 11;
        d.mode = EventData.CandyMode.SHARED;
        d.respawnSeconds = 42;
        d.reward = new ItemStack(Items.EMERALD, 7);
        d.rewardCommand = "say %player% won";
        d.defaultScareLevel = 3;
        d.holdScares = false;
        d.preventCarry = true;
        d.carryDropSeconds = 9;
        d.freecamGuard = false;
        d.grabIntervalSeconds = 33;
        d.spawns.add(new EventData.CandySpawn(4, new BlockPos(1, 2, 3), 2));
        d.spawns.add(new EventData.CandySpawn(9, new BlockPos(-7, 64, 100), 0));
        d.nextSpawnId = 10;
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        d.scareLevels.put(one, 1);
        d.scareLevels.put(two, 3);
        EventData.Progress p = new EventData.Progress();
        p.name = "Alice";
        p.collected.add(4);
        p.collected.add(9);
        p.count = 2;
        p.completed = true;
        d.progress.put(one, p);

        EventData l = EventData.load(d.save(new CompoundTag()));
        helper.assertTrue(l.dimension.equals(d.dimension), "dimension");
        helper.assertTrue(new BlockPos(-5, 60, 12).equals(l.corner1) && new BlockPos(40, 80, -33).equals(l.corner2), "corners");
        helper.assertTrue(l.active && l.target == 11 && l.mode == EventData.CandyMode.SHARED && l.respawnSeconds == 42, "basic settings");
        helper.assertTrue(ItemStack.matches(d.reward, l.reward) && l.rewardCommand.equals("say %player% won"), "reward");
        helper.assertTrue(l.defaultScareLevel == 3 && !l.holdScares && l.preventCarry && l.carryDropSeconds == 9 && !l.freecamGuard
                && l.grabIntervalSeconds == 33, "scare and restriction settings");
        helper.assertTrue(l.spawns.equals(d.spawns) && l.nextSpawnId == 10, "candy spawns " + l.spawns);
        helper.assertTrue(l.scareLevels.equals(d.scareLevels), "per-player scare levels");
        EventData.Progress lp = l.progress.get(one);
        helper.assertTrue(lp != null && lp.name.equals("Alice") && lp.count == 2 && lp.completed
                && lp.collected.contains(4) && lp.collected.contains(9) && lp.collected.size() == 2, "player progress");
        helper.assertTrue(l.zone() != null && l.zone().equals(d.zone()), "zone box");

        // Out-of-range values in an old or hand-edited save are clamped, never crash.
        CompoundTag bad = d.save(new CompoundTag());
        bad.putInt("target", -5);
        bad.putInt("defaultScareLevel", 99);
        bad.putString("mode", "NOT_A_MODE");
        bad.putString("dimension", "not a dimension!!");
        EventData fixed = EventData.load(bad);
        helper.assertTrue(fixed.target >= 1 && fixed.defaultScareLevel == 3 && fixed.mode == EventData.CandyMode.PER_PLAYER, "bad values are clamped");
        helper.succeed();
    }

    // ---- network packets ----

    private static <T> T roundTrip(T msg, BiConsumer<T, FriendlyByteBuf> encode, Function<FriendlyByteBuf, T> decode) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        encode.accept(msg, buf);
        T out = decode.apply(buf);
        if (buf.isReadable()) throw new GameTestAssertException("packet left " + buf.readableBytes() + " unread bytes: " + msg);
        return out;
    }

    @GameTest(template = ARENA, batch = "packets", timeoutTicks = 40)
    public static void everyPacketEncodesAndDecodesToTheSameValues(GameTestHelper helper) {
        S2CStatus status = new S2CStatus(true, false, true, false, true, 12, 15, 0b101);
        helper.assertTrue(status.equals(roundTrip(status, S2CStatus::encode, S2CStatus::decode)), "S2CStatus");
        S2CStatus status2 = new S2CStatus(false, true, false, true, false, 0, 1, 0);
        helper.assertTrue(status2.equals(roundTrip(status2, S2CStatus::encode, S2CStatus::decode)), "S2CStatus (other flags)");

        for (int type = 0; type <= 9; type++) {
            S2CScare scare = new S2CScare(type, type % 4);
            helper.assertTrue(scare.equals(roundTrip(scare, S2CScare::encode, S2CScare::decode)), "S2CScare type " + type);
        }
        S2CHandGrab grab = new S2CHandGrab(1234, -0.6f, 0.8f);
        helper.assertTrue(grab.equals(roundTrip(grab, S2CHandGrab::encode, S2CHandGrab::decode)), "S2CHandGrab");
        S2CCandyVisibility vis = new S2CCandyVisibility(77, true);
        helper.assertTrue(vis.equals(roundTrip(vis, S2CCandyVisibility::encode, S2CCandyVisibility::decode)), "S2CCandyVisibility");

        S2CCandySync sync = new S2CCandySync(new ResourceLocation("minecraft:overworld"), List.of(
                new S2CCandySync.Entry(1, new BlockPos(5, 64, -9), 2, true),
                new S2CCandySync.Entry(300, new BlockPos(-100, -60, 100), 0, false)));
        helper.assertTrue(sync.equals(roundTrip(sync, S2CCandySync::encode, S2CCandySync::decode)), "S2CCandySync");
        S2CCandySync empty = new S2CCandySync(new ResourceLocation("minecraft:the_nether"), List.of());
        helper.assertTrue(empty.equals(roundTrip(empty, S2CCandySync::encode, S2CCandySync::decode)), "S2CCandySync (empty)");

        C2SAdminAction action = new C2SAdminAction(AdminAction.SET_PLAYER_LEVEL, 3, UUID.randomUUID().toString());
        helper.assertTrue(action.equals(roundTrip(action, C2SAdminAction::encode, C2SAdminAction::decode)), "C2SAdminAction");
        C2SFreecamState cam = new C2SFreecamState(true);
        helper.assertTrue(cam.equals(roundTrip(cam, C2SFreecamState::encode, C2SFreecamState::decode)), "C2SFreecamState");

        AdminSnapshot s = new AdminSnapshot();
        s.active = true;
        s.corner1 = new BlockPos(1, 2, 3);
        s.corner2 = null;
        s.dimension = "minecraft:overworld";
        s.spawnCount = 14;
        s.sharedMode = true;
        s.respawnSeconds = 30;
        s.target = 9;
        s.reward = new ItemStack(Items.APPLE, 5);
        s.rewardCommand = "say hi";
        s.defaultLevel = 3;
        s.holdScares = false;
        s.preventCarry = true;
        s.carryDropSeconds = 7;
        s.freecamGuard = false;
        s.holdHandsLoaded = true;
        s.carryOnLoaded = false;
        s.grabIntervalSeconds = 45;
        s.players.add(new AdminSnapshot.PlayerRow(UUID.randomUUID(), "Alice", true, true, 3, false, 2, true));
        s.players.add(new AdminSnapshot.PlayerRow(UUID.randomUUID(), "Bob", false, false, 15, true, 1, false));
        S2CAdminData data = roundTrip(new S2CAdminData(s, true), S2CAdminData::encode, S2CAdminData::decode);
        AdminSnapshot r = data.snapshot();
        helper.assertTrue(data.open() && r.active && r.corner2 == null && new BlockPos(1, 2, 3).equals(r.corner1), "snapshot zone");
        helper.assertTrue(r.dimension.equals(s.dimension) && r.spawnCount == 14 && r.sharedMode && r.respawnSeconds == 30 && r.target == 9, "snapshot candy");
        helper.assertTrue(ItemStack.matches(s.reward, r.reward) && r.rewardCommand.equals("say hi"), "snapshot reward");
        helper.assertTrue(r.defaultLevel == 3 && !r.holdScares && r.preventCarry && r.carryDropSeconds == 7 && !r.freecamGuard
                && r.holdHandsLoaded && !r.carryOnLoaded && r.grabIntervalSeconds == 45, "snapshot settings");
        helper.assertTrue(r.players.equals(s.players), "snapshot players");
        helper.succeed();
    }

    // ---- admin permissions and validation ----

    @GameTest(template = ARENA, batch = "admin", timeoutTicks = 100)
    public static void adminActionsAreOperatorOnlyAndEveryValueIsValidated(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        ServerPlayer rando = a.bot("not_op", 5, 1, 5);
        ServerPlayer admin = a.bot("is_op", 20, 1, 20);
        a.server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            AdminActions.handle(rando, AdminAction.SET_TARGET, 5, "");
            AdminActions.handle(rando, AdminAction.CORNER_1, 0, "");
            AdminActions.handle(rando, AdminAction.TOGGLE_MODE, 0, "");
            AdminActions.handle(rando, AdminAction.START, 0, "");
            a.assertEquals(15, a.data.target, "a non-operator must not change the target");
            helper.assertTrue(a.data.corner1 == null, "a non-operator must not set a corner");
            a.assertEquals(EventData.CandyMode.PER_PLAYER, a.data.mode, "a non-operator must not change the mode");

            AdminActions.handle(admin, AdminAction.SET_TARGET, 5, "");
            a.assertEquals(5, a.data.target, "an operator can set the target");
            AdminActions.handle(admin, AdminAction.SET_TARGET, 99999, "");
            a.assertEquals(256, a.data.target, "target is clamped to 256");
            AdminActions.handle(admin, AdminAction.SET_TARGET, -4, "");
            a.assertEquals(1, a.data.target, "target is at least 1");
            AdminActions.handle(admin, AdminAction.SET_RESPAWN, 1, "");
            a.assertEquals(5, a.data.respawnSeconds, "respawn is at least 5 s");
            AdminActions.handle(admin, AdminAction.SET_RESPAWN, 99999, "");
            a.assertEquals(600, a.data.respawnSeconds, "respawn is at most 600 s");
            AdminActions.handle(admin, AdminAction.SET_CARRY_DROP, 500, "");
            a.assertEquals(60, a.data.carryDropSeconds, "carry drop is at most 60 s");
            AdminActions.handle(admin, AdminAction.SET_GRAB_INTERVAL, 99999, "");
            a.assertEquals(600, a.data.grabIntervalSeconds, "grab interval is at most 600 s");
            AdminActions.handle(admin, AdminAction.SET_GRAB_INTERVAL, -5, "");
            a.assertEquals(0, a.data.grabIntervalSeconds, "grab interval is never negative");
            AdminActions.handle(admin, AdminAction.SET_DEFAULT_LEVEL, 7, "");
            a.assertEquals(3, a.data.defaultScareLevel, "default scare level is at most 3");
            AdminActions.handle(admin, AdminAction.SET_DEFAULT_LEVEL, 0, "");
            a.assertEquals(1, a.data.defaultScareLevel, "default scare level is at least 1");

            UUID target = UUID.randomUUID();
            AdminActions.handle(admin, AdminAction.SET_PLAYER_LEVEL, 9, target.toString());
            a.assertEquals(3, a.data.levelFor(target), "player scare level is clamped to 3");
            AdminActions.handle(admin, AdminAction.SET_PLAYER_LEVEL, 2, "not a uuid");
            AdminActions.handle(admin, AdminAction.SET_PLAYER_LEVEL, 2, "");
            a.assertEquals(1, a.data.scareLevels.size(), "a malformed player id changes nothing");
            AdminActions.handle(admin, 424242, 1, "garbage");
            a.assertEquals(1, a.data.scareLevels.size(), "an unknown action changes nothing");

            AdminActions.handle(admin, AdminAction.TOGGLE_MODE, 0, "");
            a.assertEquals(EventData.CandyMode.SHARED, a.data.mode, "toggle mode");
            AdminActions.handle(admin, AdminAction.TOGGLE_HOLD_SCARES, 0, "");
            helper.assertFalse(a.data.holdScares, "toggle hand-holding scares");
            AdminActions.handle(admin, AdminAction.TOGGLE_PREVENT_CARRY, 0, "");
            helper.assertTrue(a.data.preventCarry, "toggle carry prevention");
            AdminActions.handle(admin, AdminAction.TOGGLE_FREECAM_GUARD, 0, "");
            helper.assertFalse(a.data.freecamGuard, "toggle freecam guard");

            AdminActions.handle(admin, AdminAction.SET_REWARD_COMMAND, 0, "  /say hello %player%  ");
            a.assertEquals("say hello %player%", a.data.rewardCommand, "reward command is trimmed and loses its slash");
            AdminActions.handle(admin, AdminAction.SET_REWARD_COMMAND, 0, "x".repeat(1000));
            helper.assertTrue(a.data.rewardCommand.length() <= 256, "reward command is capped at 256 characters");
            AdminActions.handle(admin, AdminAction.REWARD_FROM_HAND, 0, "");
            a.assertEquals(Items.DIAMOND, a.data.reward.getItem(), "an empty hand does not replace the reward");
            admin.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE, 4));
            AdminActions.handle(admin, AdminAction.REWARD_FROM_HAND, 0, "");
            helper.assertTrue(a.data.reward.getItem() == Items.APPLE && a.data.reward.getCount() == 4, "reward taken from the held stack");

            // zone and candy editing
            AdminActions.handle(admin, AdminAction.ADD_CANDY, 0, "");
            a.assertEquals(0, a.data.spawns.size(), "candy cannot be added without a zone");
            AdminActions.handle(admin, AdminAction.START, 0, "");
            helper.assertFalse(a.data.active, "cannot start without a zone");
            a.moveTo(admin, 10, 1, 10);
            AdminActions.handle(admin, AdminAction.CORNER_1, 0, "");
            a.moveTo(admin, 30, 3, 30);
            AdminActions.handle(admin, AdminAction.CORNER_2, 0, "");
            helper.assertTrue(a.data.zone() != null, "both corners set the zone");
            AdminActions.handle(admin, AdminAction.START, 0, "");
            helper.assertFalse(a.data.active, "cannot start without candy");
            a.moveTo(admin, 5, 1, 5);
            AdminActions.handle(admin, AdminAction.ADD_CANDY, 0, "");
            a.assertEquals(0, a.data.spawns.size(), "candy cannot be added outside the zone");
            a.moveTo(admin, 20, 1, 20);
            AdminActions.handle(admin, AdminAction.ADD_CANDY, 0, "");
            AdminActions.handle(admin, AdminAction.ADD_CANDY, 0, "");
            a.assertEquals(1, a.data.spawns.size(), "the same spot cannot hold two candies");
            AdminActions.handle(admin, AdminAction.START, 0, "");
            helper.assertTrue(a.data.active, "start works with a zone and candy");
            AdminActions.handle(admin, AdminAction.STOP, 0, "");
            helper.assertFalse(a.data.active, "stop works");
            AdminActions.handle(admin, AdminAction.REMOVE_NEAREST_CANDY, 0, "");
            a.assertEquals(0, a.data.spawns.size(), "nearest candy removed");
            AdminActions.handle(admin, AdminAction.RESET_PROGRESS, 0, "");
            AdminActions.handle(admin, AdminAction.CLEAR_ZONE, 0, "");
            helper.assertTrue(a.data.zone() == null, "zone cleared");
        } finally {
            a.server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        a.close();
        helper.succeed();
    }

    // ---- commands ----

    @GameTest(template = ARENA, batch = "commands", timeoutTicks = 80)
    public static void commandsWorkForOperatorsAndAreHiddenFromEveryoneElse(GameTestHelper helper) throws CommandSyntaxException {
        Arena a = Arena.open(helper);
        CommandDispatcher<CommandSourceStack> d = a.server.getCommands().getDispatcher();
        CommandSourceStack op = a.server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        CommandSourceStack nobody = a.server.createCommandSourceStack().withSuppressedOutput().withPermission(0);
        ServerPlayer p = a.bot("cmd_target", 20, 1, 20);
        ServerPlayer q = a.bot("cmd_partner", 21, 1, 20);

        a.assertEquals(1, d.execute("halloween status", op), "/halloween status");
        for (String c : new String[]{"halloween status", "halloween start", "halloween stop", "halloween admin",
                "halloween test grab", "halloween test figure", "halloween test eyes", "halloween test hypno"}) {
            boolean hidden = false;
            try {
                d.execute(c, nobody);
            } catch (CommandSyntaxException e) {
                hidden = true;
            }
            helper.assertTrue(hidden, "\"/" + c + "\" must not be available without operator permission");
        }

        a.assertEquals(0, d.execute("halloween start", op), "/halloween start refuses without a zone");
        helper.assertFalse(a.data.active, "still stopped");
        a.zone(5, 1, 5, 35, 5, 35);
        a.candy(10, 1, 10);
        a.assertEquals(1, d.execute("halloween start", op), "/halloween start");
        helper.assertTrue(a.data.active, "started by command");
        a.assertEquals(1, d.execute("halloween stop", op), "/halloween stop");
        helper.assertFalse(a.data.active, "stopped by command");

        a.assertEquals(1, d.execute("halloween test figure 3 cmd_target", op), "/halloween test figure 3 <player>");
        a.assertEquals(1, d.execute("halloween test figure", op.withEntity(p)), "/halloween test figure on yourself");
        a.assertEquals(1, d.execute("halloween test chase cmd_target", op), "/halloween test chase");
        a.assertEquals(1, d.execute("halloween test eyes", op.withEntity(p)), "/halloween test eyes");
        a.assertEquals(1, d.execute("halloween test eyes wall", op.withEntity(p)), "/halloween test eyes wall");
        a.assertEquals(1, d.execute("halloween test hypno", op.withEntity(p)), "/halloween test hypno");
        a.assertEquals(1, d.execute("halloween admin", op.withEntity(p)), "/halloween admin");

        boolean rejected = false;
        try {
            d.execute("halloween test figure 9", op.withEntity(p));
        } catch (CommandSyntaxException e) {
            rejected = true;
        }
        helper.assertTrue(rejected, "figure level 9 is out of range");

        a.assertEquals(0, d.execute("halloween test grab cmd_target", op), "/halloween test grab refuses while not holding hands");
        Arena.hold(p, q);
        a.assertEquals(1, d.execute("halloween test grab cmd_target", op), "/halloween test grab while holding hands");
        a.assertEquals(0, d.execute("halloween test grab cmd_target", op), "/halloween test grab refuses while one is running");
        a.close();
        helper.succeed();
    }

    @GameTest(template = ARENA, batch = "freecam", timeoutTicks = 80)
    public static void freecamReportsAreOnlyAcceptedFromPlayersInsideTheZone(GameTestHelper helper) {
        Arena a = Arena.open(helper);
        a.zone(5, 1, 5, 35, 5, 35);
        a.candy(10, 1, 10);
        a.start();
        ServerPlayer inside = a.bot("cam_inside", 20, 1, 20);
        ServerPlayer outside = a.bot("cam_outside", 38, 1, 20);
        helper.runAtTickTime(20, () -> {
            EventManager.setFreecam(inside, true);
            EventManager.setFreecam(outside, true);
            helper.assertTrue(EventManager.isFreecamFlagged(inside.getUUID()), "an in-zone report is recorded");
            helper.assertFalse(EventManager.isFreecamFlagged(outside.getUUID()), "a report from outside the zone is ignored");
        });
        helper.runAtTickTime(40, () -> {
            EventManager.setFreecam(inside, false);
            helper.assertFalse(EventManager.isFreecamFlagged(inside.getUUID()), "the flag clears");
            a.close();
            helper.succeed();
        });
    }
}
