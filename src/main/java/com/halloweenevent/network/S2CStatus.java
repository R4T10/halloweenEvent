package com.halloweenevent.network;

import com.halloweenevent.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Event/zone/progress state for the receiving player. Sent only when something changes. */
public record S2CStatus(boolean active, boolean inZone, boolean completed, boolean freecamGuard, boolean holding,
                        int count, int target) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte((active ? 1 : 0) | (inZone ? 2 : 0) | (completed ? 4 : 0) | (freecamGuard ? 8 : 0) | (holding ? 16 : 0));
        buf.writeVarInt(count);
        buf.writeVarInt(target);
    }

    public static S2CStatus decode(FriendlyByteBuf buf) {
        int f = buf.readByte();
        return new S2CStatus((f & 1) != 0, (f & 2) != 0, (f & 4) != 0, (f & 8) != 0, (f & 16) != 0, buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(S2CStatus msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.status(msg));
    }
}
