package com.halloweenevent.network;

import com.halloweenevent.server.EventManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client report of a detected free camera; informational only (logged and shown to admins). */
public record C2SFreecamState(boolean active) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
    }

    public static C2SFreecamState decode(FriendlyByteBuf buf) {
        return new C2SFreecamState(buf.readBoolean());
    }

    public static void handle(C2SFreecamState msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) EventManager.setFreecam(sender, msg.active);
    }
}
