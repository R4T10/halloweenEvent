package com.halloweenevent.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Client mirror of the server state relevant to this player. Written only by packet handlers. */
public final class ClientState {
    public record Candy(BlockPos pos, int variant) {}

    public static boolean active;
    public static boolean inZone;
    public static boolean completed;
    public static boolean freecamGuard;
    /** Holding a hand (Hold Hands, server-confirmed); thins the zone fog. */
    public static boolean holding;
    public static int count;
    public static int target = 15;

    public static ResourceLocation candyDimension;
    public static final Int2ObjectOpenHashMap<Candy> CANDIES = new Int2ObjectOpenHashMap<>();
    public static final IntOpenHashSet HIDDEN = new IntOpenHashSet();

    private ClientState() {}

    public static void reset() {
        active = inZone = completed = freecamGuard = holding = false;
        count = 0;
        target = 15;
        candyDimension = null;
        CANDIES.clear();
        HIDDEN.clear();
    }
}
