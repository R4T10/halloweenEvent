package com.halloweenevent.network;

import com.halloweenevent.HalloweenEventMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Net {
    private static final String VERSION = "3";
    /**
     * Servers require the mod on clients; clients may still join servers without it
     * (they never send anything unless the server has activated the event or opened the admin screen).
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(HalloweenEventMod.MODID, "main"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), VERSION::equals);

    private Net() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(S2CStatus.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStatus::encode).decoder(S2CStatus::decode).consumerMainThread(S2CStatus::handle).add();
        CHANNEL.messageBuilder(S2CCandySync.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CCandySync::encode).decoder(S2CCandySync::decode).consumerMainThread(S2CCandySync::handle).add();
        CHANNEL.messageBuilder(S2CCandyVisibility.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CCandyVisibility::encode).decoder(S2CCandyVisibility::decode).consumerMainThread(S2CCandyVisibility::handle).add();
        CHANNEL.messageBuilder(S2CScare.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CScare::encode).decoder(S2CScare::decode).consumerMainThread(S2CScare::handle).add();
        CHANNEL.messageBuilder(S2CAdminData.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CAdminData::encode).decoder(S2CAdminData::decode).consumerMainThread(S2CAdminData::handle).add();
        CHANNEL.messageBuilder(C2SAdminAction.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SAdminAction::encode).decoder(C2SAdminAction::decode).consumerMainThread(C2SAdminAction::handle).add();
        CHANNEL.messageBuilder(C2SFreecamState.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SFreecamState::encode).decoder(C2SFreecamState::decode).consumerMainThread(C2SFreecamState::handle).add();
        CHANNEL.messageBuilder(S2CHandGrab.class, id++, NetworkDirection.PLAY_TO_CLIENT)
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
