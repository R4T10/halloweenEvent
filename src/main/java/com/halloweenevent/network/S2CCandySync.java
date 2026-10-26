package com.halloweenevent.network;

import com.halloweenevent.client.ClientPacketHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Full candy list for the receiving player; empty when the event is stopped. */
public record S2CCandySync(ResourceLocation dimension, List<Entry> candies) {
    public record Entry(int id, BlockPos pos, int variant, boolean hidden) {}

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeCollection(candies, (b, e) -> {
            b.writeVarInt(e.id());
            b.writeBlockPos(e.pos());
            b.writeByte(e.variant());
            b.writeBoolean(e.hidden());
        });
    }

    public static S2CCandySync decode(FriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        List<Entry> list = buf.readList(b -> new Entry(b.readVarInt(), b.readBlockPos(), b.readByte(), b.readBoolean()));
        return new S2CCandySync(dim, list);
    }

    public static void handle(S2CCandySync msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.candies(msg));
    }
}
