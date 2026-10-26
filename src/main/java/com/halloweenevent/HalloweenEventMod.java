package com.halloweenevent;

import com.halloweenevent.compat.CarryOnCompat;
import com.halloweenevent.network.Net;
import com.halloweenevent.registry.ModItems;
import com.halloweenevent.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(HalloweenEventMod.MODID)
public class HalloweenEventMod {
    public static final String MODID = "halloweenevent";
    public static final Logger LOGGER = LogUtils.getLogger();

    public HalloweenEventMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        modBus.addListener(ModItems::addToTabs);
        Net.register();
        CarryOnCompat.init();
    }
}
