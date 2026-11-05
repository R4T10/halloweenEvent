package com.halloweenevent.network;

import com.halloweenevent.HalloweenEventMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Net {
    private static final String VERSION = "7";
    /**
     * Servers require the mod on clients; clients may still join servers without it
     * (they never send anything unless the server has activated the event or opened the admin screen).
     */
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(HalloweenEventMod.MODID, "main");
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID, () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), VERSION::equals);

    /** Wire ids of the packets (kept explicit so the game tests can decode what a bot receives). */
    public static final int ID_STATUS = 0;
    public static final int ID_CANDY_SYNC = 1;
    public static final int ID_CANDY_VISIBILITY = 2;
    public static final int ID_SCARE = 3;
    public static final int ID_ADMIN_DATA = 4;
    public static final int ID_ADMIN_ACTION = 5;
    public static final int ID_FREECAM = 6;
    public static final int ID_HAND_GRAB = 7;

    private Net() {}

    public static void register() {
        CHANNEL.messageBuilder(S2CStatus.class, ID_STATUS, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStatus::encode).decoder(S2CStatus::decode).consumerMainThread(S2CStatus::handle).add();
        CHANNEL.messageBuilder(S2CCandySync.class, ID_CANDY_SYNC, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CCandySync::encode).decoder(S2CCandySync::decode).consumerMainThread(S2CCandySync::handle).add();
        CHANNEL.messageBuilder(S2CCandyVisibility.class, ID_CANDY_VISIBILITY, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CCandyVisibility::encode).decoder(S2CCandyVisibility::decode).consumerMainThread(S2CCandyVisibility::handle).add();
        CHANNEL.messageBuilder(S2CScare.class, ID_SCARE, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CScare::encode).decoder(S2CScare::decode).consumerMainThread(S2CScare::handle).add();
        CHANNEL.messageBuilder(S2CAdminData.class, ID_ADMIN_DATA, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CAdminData::encode).decoder(S2CAdminData::decode).consumerMainThread(S2CAdminData::handle).add();
        CHANNEL.messageBuilder(C2SAdminAction.class, ID_ADMIN_ACTION, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SAdminAction::encode).decoder(C2SAdminAction::decode).consumerMainThread(C2SAdminAction::handle).add();
        CHANNEL.messageBuilder(C2SFreecamState.class, ID_FREECAM, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SFreecamState::encode).decoder(C2SFreecamState::decode).consumerMainThread(C2SFreecamState::handle).add();
        CHANNEL.messageBuilder(S2CHandGrab.class, ID_HAND_GRAB, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CHandGrab::encode).decoder(S2CHandGrab::decode).consumerMainThread(S2CHandGrab::handle).add();
    }

    public static void toPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void toAll(Object msg) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), msg);
    }

    public static void toServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }
}
