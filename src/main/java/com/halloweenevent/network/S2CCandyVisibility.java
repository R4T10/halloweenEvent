package com.halloweenevent.network;

import com.halloweenevent.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record S2CCandyVisibility(int id, boolean visible) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeBoolean(visible);
    }

    public static S2CCandyVisibility decode(FriendlyByteBuf buf) {
        return new S2CCandyVisibility(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(S2CCandyVisibility msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.candyVisibility(msg));
    }
}
