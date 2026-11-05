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
    /** Victim-only hallucination: a figure chases the player for 10 s. The level (1-3) tints the fog: red at 3 only. */
    public static final int CHASE = 7;
    /** Screen eyes; level 0 = a swarm at the edges, 1 = the whole screen. Admin test. */
    public static final int EYES = 8;
    /** The red hypnosis eye. Admin test. */
    public static final int HYPNO = 9;
    /** The carry face (scream, heartbeat) for 3 s. Admin prank. */
    public static final int FACE = 10;
    /** The freecam face (scream, glitch). Admin prank. */
    public static final int FREECAM_FACE = 11;
    /** Ends every scare on screen at once (eyes, chase, faces, text, fog spike). Admin. */
    public static final int CLEAR = 12;

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
