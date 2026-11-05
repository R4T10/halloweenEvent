package com.halloweenevent.gametest;

import com.halloweenevent.compat.HoldHandsCompat;
import com.halloweenevent.data.EventData;
import com.halloweenevent.data.EventData.CandySpawn;
import com.halloweenevent.network.Net;
import com.halloweenevent.network.S2CAdminData;
import com.halloweenevent.network.S2CCandySync;
import com.halloweenevent.network.S2CCandyVisibility;
import com.halloweenevent.network.S2CHandGrab;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.network.S2CStatus;
import com.halloweenevent.server.EventManager;
import com.mojang.authlib.GameProfile;
import com.seaz.holdhands.HoldManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * One test's world: resets the global event state, creates bot players and puts them where the test wants.
 * Coordinates are relative to the arena (a 41 x 8 x 41 stone floor; y = 1 is standing level).
 * <p>
 * Bots are real {@link ServerPlayer}s placed in the player list, so every part of the mod sees them like a
 * connected player. They never move on their own, never take fall damage and do not regenerate.
 */
public final class Arena {
    private static final List<ServerPlayer> BOTS = new ArrayList<>();

    public final GameTestHelper helper;
    public final ServerLevel level;
    public final MinecraftServer server;
    public final EventData data;

    private Arena(GameTestHelper helper) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
        this.data = EventData.get(server);
    }

    /** Starts a clean test: previous bots removed, event stopped, every setting back to its default. */
    public static Arena open(GameTestHelper helper) {
        Arena a = new Arena(helper);
        a.removeBots();
        EventManager.onServerStopped();
        EventData d = a.data;
        d.active = false;
        d.setCorner(true, null);
        d.setCorner(false, null);
        d.dimension = Level.OVERWORLD;
        d.spawns.clear();
        d.nextSpawnId = 1;
        d.mode = EventData.CandyMode.PER_PLAYER;
        d.respawnSeconds = 60;
        d.target = 15;
        d.reward = new ItemStack(Items.DIAMOND, 3);
        d.rewardCommand = "";
        d.defaultScareLevel = 2;
        d.scareLevels.clear();
        d.holdScares = true;
        d.preventCarry = false;
        d.carryDropSeconds = 5;
        d.freecamGuard = true;
        d.grabIntervalSeconds = 0;
        d.progress.clear();
        // Bots must not heal between damage checks.
        a.level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, a.server);
        return a;
    }

    /** Removes the bots, stops the event and restores the world rules. Call at the end of every test. */
    public void close() {
        removeBots();
        EventManager.onServerStopped();
        data.active = false;
        data.grabIntervalSeconds = 0;
        level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(true, server);
    }

    // ---- zone, candy, event ----

    public void zone(int x1, int y1, int z1, int x2, int y2, int z2) {
        data.dimension = level.dimension();
        data.setCorner(true, helper.absolutePos(new BlockPos(x1, y1, z1)));
        data.setCorner(false, helper.absolutePos(new BlockPos(x2, y2, z2)));
    }

    public BlockPos candy(int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        data.spawns.add(new CandySpawn(data.nextSpawnId++, pos, 0));
        return pos;
    }

    public void start() {
        EventManager.start(server);
    }

    // ---- bots ----

    public ServerPlayer bot(String name, int x, int y, int z) {
        ServerPlayer bot = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name)) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
        try {
            BotConnection connection = new BotConnection();
            server.getPlayerList().placeNewPlayer(connection, bot);
            CONNECTIONS.put(bot.getUUID(), connection);
        } catch (RuntimeException e) {
            com.halloweenevent.HalloweenEventMod.LOGGER.error("Could not place bot {}", name, e);
            throw e;
        }
        bot.setGameMode(GameType.SURVIVAL);
        bot.setHealth(bot.getMaxHealth());
        BOTS.add(bot);
        moveTo(bot, x, y, z);
        return bot;
    }

    /**
     * A connection that goes nowhere but remembers every packet sent to it. Forge's network filters need
     * {@code Connection.channel()} to be a real channel with a "packet_handler" in its pipeline (a bare
     * {@code new Connection(...)} throws a NullPointerException while the player is placed).
     */
    private static final class BotConnection extends Connection {
        final List<Packet<?>> sent = new ArrayList<>();
        private final EmbeddedChannel channel = new EmbeddedChannel();

        BotConnection() {
            super(PacketFlow.SERVERBOUND);
            channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
            // Forge decides vanilla/modded from this attribute (NetworkConstants.FML_NETVERSION); act like a Forge client.
            channel.attr(AttributeKey.<String>valueOf("fml:netversion")).set("FML3");
        }

        @Override
        public Channel channel() {
            return channel;
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            sent.add(packet);
        }
    }

    private static final java.util.Map<UUID, BotConnection> CONNECTIONS = new java.util.HashMap<>();

    // ---- what the bots received (decoded from this mod's channel) ----

    /** Every message of one kind that the server sent to this bot so far, oldest first. */
    public <T> List<T> received(ServerPlayer bot, int discriminator, Function<FriendlyByteBuf, T> decoder) {
        List<T> out = new ArrayList<>();
        BotConnection c = CONNECTIONS.get(bot.getUUID());
        if (c == null) return out;
        for (Packet<?> p : List.copyOf(c.sent)) {
            if (!(p instanceof ClientboundCustomPayloadPacket payload) || !payload.getIdentifier().equals(Net.CHANNEL_ID)) continue;
            FriendlyByteBuf buf = payload.getData();
            if (buf.readByte() == discriminator) out.add(decoder.apply(buf));
        }
        return out;
    }

    public List<S2CScare> scares(ServerPlayer bot) {
        return received(bot, Net.ID_SCARE, S2CScare::decode);
    }

    public List<S2CStatus> statuses(ServerPlayer bot) {
        return received(bot, Net.ID_STATUS, S2CStatus::decode);
    }

    public List<S2CHandGrab> grabs(ServerPlayer bot) {
        return received(bot, Net.ID_HAND_GRAB, S2CHandGrab::decode);
    }

    public List<S2CCandyVisibility> candyChanges(ServerPlayer bot) {
        return received(bot, Net.ID_CANDY_VISIBILITY, S2CCandyVisibility::decode);
    }

    public List<S2CCandySync> candyLists(ServerPlayer bot) {
        return received(bot, Net.ID_CANDY_SYNC, S2CCandySync::decode);
    }

    public List<S2CAdminData> adminData(ServerPlayer bot) {
        return received(bot, Net.ID_ADMIN_DATA, S2CAdminData::decode);
    }

    /** Just the scare types, for readable assertions: [1, 2, 3] = whisper, text, figure. */
    public List<Integer> scareTypes(ServerPlayer bot) {
        return scares(bot).stream().map(S2CScare::type).toList();
    }

    /** Make a player created outside {@link #bot} (for example by a respawn) get removed at the end of the test. */
    public static void track(ServerPlayer player) {
        BOTS.add(player);
    }

    /** Stand the bot on the middle of a block of the arena. */
    public void moveTo(ServerPlayer bot, int x, int y, int z) {
        BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
        bot.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0f, 0f);
    }

    private void removeBots() {
        for (ServerPlayer bot : BOTS) {
            if (server.getPlayerList().getPlayer(bot.getUUID()) != null) server.getPlayerList().remove(bot);
        }
        BOTS.clear();
        CONNECTIONS.clear();
    }

    // ---- hand holding, through Hold Hands' own public API ----

    /** A offers, B accepts: the same two calls the H key makes. Both bots must be within reach. */
    public static void hold(ServerPlayer a, ServerPlayer b) {
        HoldManager.onKey(a, b);
        HoldManager.onKey(b, a);
    }

    /** A is holding and presses H again: Hold Hands releases the pair (reason LET_GO). */
    public static void letGo(ServerPlayer a, ServerPlayer b) {
        HoldManager.onKey(a, b);
    }

    public static boolean holding(ServerPlayer p) {
        return HoldHandsCompat.isHolding(p);
    }

    public static boolean holdingEachOther(ServerPlayer a, ServerPlayer b) {
        return HoldHandsCompat.partner(a) == b && HoldHandsCompat.partner(b) == a;
    }

    // ---- small assertions with readable messages ----

    public void assertEquals(Object expected, Object actual, String what) {
        helper.assertTrue(java.util.Objects.equals(expected, actual), what + ": expected " + expected + " but was " + actual);
    }

    public void assertHealth(ServerPlayer p, float expected, String what) {
        helper.assertTrue(Math.abs(p.getHealth() - expected) < 0.01f,
                what + ": expected health " + expected + " but was " + p.getHealth());
    }

    public int count(ServerPlayer p) {
        EventData.Progress progress = data.progress.get(p.getUUID());
        return progress == null ? 0 : progress.count;
    }

    public boolean completed(ServerPlayer p) {
        EventData.Progress progress = data.progress.get(p.getUUID());
        return progress != null && progress.completed;
    }
}
