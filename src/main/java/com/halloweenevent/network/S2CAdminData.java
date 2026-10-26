package com.halloweenevent.network;

import com.halloweenevent.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** @param open true to open the admin screen, false to refresh it if it is already open. */
public record S2CAdminData(AdminSnapshot snapshot, boolean open) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        snapshot.write(buf);
    }

    public static S2CAdminData decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        return new S2CAdminData(AdminSnapshot.read(buf), open);
    }

    public static void handle(S2CAdminData msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.adminData(msg));
    }
}
