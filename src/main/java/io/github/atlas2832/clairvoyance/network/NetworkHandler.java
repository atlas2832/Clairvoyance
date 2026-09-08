package io.github.atlas2832.clairvoyance.network;

import io.github.atlas2832.clairvoyance.Clairvoyance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Clairvoyance.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++,
                ClairvoyanceActionPacket.class,
                ClairvoyanceActionPacket::encode,
                ClairvoyanceActionPacket::decode,
                ClairvoyanceActionPacket::handle);
    }
}
