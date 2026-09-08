package io.github.atlas2832.clairvoyance;

import io.github.atlas2832.clairvoyance.client.ClientKeys;
import io.github.atlas2832.clairvoyance.compat.CuriosCompat;
import io.github.atlas2832.clairvoyance.init.ModItems;
import io.github.atlas2832.clairvoyance.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Clairvoyance.MODID)
public class Clairvoyance {

    public static final String MODID = "clairvoyance";

    public Clairvoyance() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);
        if (CuriosCompat.isLoaded()) {
            modEventBus.addListener((InterModEnqueueEvent event) -> CuriosCompat.enqueueSlots());
        }
        ModItems.register(modEventBus);
        NetworkHandler.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (CuriosCompat.isLoaded()) {
            event.enqueueWork(() -> CuriosCompat.registerCurio(ModItems.CLAIRVOYANCE.get()));
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            ClientKeys.register(event);
        }
    }
}
