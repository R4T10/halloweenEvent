package com.halloweenevent.network;

import com.halloweenevent.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Scare trigger; all visuals and sounds are produced client-side from this 2-byte message. */
public record S2CScare(int type, int level) {
    public static final int STOP = 0;
    public static final int WHISPER = 1;
    public static final int TEXT = 2;
    public static final int FIGURE = 3;
    public static final int CARRY_ON = 4;
    public static final int CARRY_OFF = 5;
    /** Victim-only hallucination: a figure chases the player for 10 s. */
    public static final int CHASE = 7;

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(type);
        buf.writeByte(level);
    }

    public static S2CScare decode(FriendlyByteBuf buf) {
        return new S2CScare(buf.readByte(), buf.readByte());
    }

    public static void handle(S2CScare msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.scare(msg));
    }
}
