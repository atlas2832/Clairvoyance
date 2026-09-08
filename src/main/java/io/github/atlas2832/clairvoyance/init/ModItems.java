package io.github.atlas2832.clairvoyance.init;

import io.github.atlas2832.clairvoyance.Clairvoyance;
import io.github.atlas2832.clairvoyance.item.ClairvoyanceItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Clairvoyance.MODID);
    public static final RegistryObject<Item> CLAIRVOYANCE = ITEMS.register("clairvoyance",
            () -> new ClairvoyanceItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ModItems::addCreative);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CLAIRVOYANCE);
        }
    }
}
