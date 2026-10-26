package com.halloweenevent.network;

import com.halloweenevent.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A dark figure grabs {@code victimId} and drags them along (dirX, dirZ), away from their partner.
 * Sent to the victim (screen hand + figure) and their partner (figure); rendered at the victim's live position.
 */
public record S2CHandGrab(int victimId, float dirX, float dirZ) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(victimId);
        buf.writeFloat(dirX);
        buf.writeFloat(dirZ);
    }

    public static S2CHandGrab decode(FriendlyByteBuf buf) {
        return new S2CHandGrab(buf.readVarInt(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(S2CHandGrab msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.handGrab(msg));
    }
}
