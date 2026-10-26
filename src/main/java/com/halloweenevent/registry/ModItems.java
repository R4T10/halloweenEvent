package com.halloweenevent.registry;

import com.halloweenevent.HalloweenEventMod;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, HalloweenEventMod.MODID);

    public static final RegistryObject<Item> CANDY_CORN = candy("candy_corn");
    public static final RegistryObject<Item> LOLLIPOP = candy("lollipop");
    public static final RegistryObject<Item> WRAPPED_CANDY = candy("wrapped_candy");

    /** Spawn variants, indexed by {@code CandySpawn.variant}. */
    public static final List<RegistryObject<Item>> VARIANTS = List.of(CANDY_CORN, LOLLIPOP, WRAPPED_CANDY);

    private ModItems() {}

    private static RegistryObject<Item> candy(String name) {
        return ITEMS.register(name, () -> new Item(new Item.Properties().food(
                new FoodProperties.Builder().nutrition(2).saturationMod(0.1f).fast().alwaysEat().build())));
    }

    public static Item variant(int index) {
        return VARIANTS.get(Math.floorMod(index, VARIANTS.size())).get();
    }

    public static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            VARIANTS.forEach(item -> event.accept(item.get()));
        }
    }
}
