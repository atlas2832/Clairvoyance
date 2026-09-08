package io.github.atlas2832.clairvoyance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public class ClientHandler {
    public static void openScreen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new ClairvoyanceScreen(hand));
    }
}
