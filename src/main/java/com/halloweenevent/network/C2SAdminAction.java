package com.halloweenevent.network;

import com.halloweenevent.server.AdminActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Admin GUI request. The server re-validates permission; the client is never trusted. */
public record C2SAdminAction(int action, int value, String text) {
    private static final int MAX_TEXT = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action);
        buf.writeVarInt(value);
        buf.writeUtf(text, MAX_TEXT);
    }

    public static C2SAdminAction decode(FriendlyByteBuf buf) {
        return new C2SAdminAction(buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_TEXT));
    }

    public static void handle(C2SAdminAction msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) AdminActions.handle(sender, msg.action, msg.value, msg.text);
    }
}
