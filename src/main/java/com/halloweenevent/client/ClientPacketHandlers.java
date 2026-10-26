package com.halloweenevent.client;

import com.halloweenevent.client.screen.AdminScreen;
import com.halloweenevent.network.S2CAdminData;
import com.halloweenevent.network.S2CCandySync;
import com.halloweenevent.network.S2CCandyVisibility;
import com.halloweenevent.network.S2CHandGrab;
import com.halloweenevent.network.S2CScare;
import com.halloweenevent.network.S2CStatus;
import net.minecraft.client.Minecraft;

public final class ClientPacketHandlers {
    private ClientPacketHandlers() {}

    public static void status(S2CStatus msg) {
        ClientState.active = msg.active();
        ClientState.inZone = msg.inZone();
        ClientState.completed = msg.completed();
        ClientState.freecamGuard = msg.freecamGuard();
        ClientState.holding = msg.holding();
        ClientState.count = msg.count();
        ClientState.target = msg.target();
        if (!msg.active() || !msg.inZone()) ScareEffects.stopAll();
    }

    public static void candies(S2CCandySync msg) {
        ClientState.candyDimension = msg.dimension();
        ClientState.CANDIES.clear();
        ClientState.HIDDEN.clear();
        for (S2CCandySync.Entry e : msg.candies()) {
            ClientState.CANDIES.put(e.id(), new ClientState.Candy(e.pos(), e.variant()));
            if (e.hidden()) ClientState.HIDDEN.add(e.id());
        }
    }

    public static void candyVisibility(S2CCandyVisibility msg) {
        if (msg.visible()) ClientState.HIDDEN.remove(msg.id());
        else ClientState.HIDDEN.add(msg.id());
    }

    public static void scare(S2CScare msg) {
        ScareEffects.handle(msg.type(), msg.level());
    }

    public static void handGrab(S2CHandGrab msg) {
        ScareEffects.handGrab(msg.victimId(), msg.dirX(), msg.dirZ());
    }

    public static void adminData(S2CAdminData msg) {
        Minecraft mc = Minecraft.getInstance();
        if (msg.open()) {
            mc.setScreen(new AdminScreen(msg.snapshot()));
        } else if (mc.screen instanceof AdminScreen screen) {
            screen.update(msg.snapshot());
        }
    }
}
