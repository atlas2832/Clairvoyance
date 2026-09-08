package io.github.atlas2832.clairvoyance.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.atlas2832.clairvoyance.Clairvoyance;
import io.github.atlas2832.clairvoyance.compat.CuriosCompat;
import io.github.atlas2832.clairvoyance.init.ModItems;
import io.github.atlas2832.clairvoyance.item.ClairvoyanceItem;
import io.github.atlas2832.clairvoyance.network.ClairvoyanceActionPacket;
import io.github.atlas2832.clairvoyance.network.NetworkHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = Clairvoyance.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientKeys {
    public static KeyMapping toggleActive;
    public static KeyMapping openGui;

    public static void register(RegisterKeyMappingsEvent event) {
        toggleActive = new KeyMapping("key.clairvoyance.toggle",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.clairvoyance.category");
        event.register(toggleActive);
        openGui = new KeyMapping("key.clairvoyance.open_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.clairvoyance.category");
        event.register(openGui);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (toggleActive != null) {
            while (toggleActive.consumeClick()) {
                NetworkHandler.CHANNEL.sendToServer(new ClairvoyanceActionPacket(
                        ClairvoyanceActionPacket.Action.ITEM_TOGGLE, "", InteractionHand.MAIN_HAND));
            }
        }
        if (openGui != null) {
            while (openGui.consumeClick()) {
                openFirstGui();
            }
        }
    }

    private static void openFirstGui() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        Player player = mc.player;
        if (player.getMainHandItem().getItem() instanceof ClairvoyanceItem) {
            mc.setScreen(new ClairvoyanceScreen(InteractionHand.MAIN_HAND));
            return;
        }
        if (player.getOffhandItem().getItem() instanceof ClairvoyanceItem) {
            mc.setScreen(new ClairvoyanceScreen(InteractionHand.OFF_HAND));
            return;
        }
        if (CuriosCompat.isLoaded()) {
            var equipped = CuriosCompat.findFirstEquipped(player, ModItems.CLAIRVOYANCE.get());
            if (equipped.isPresent()) {
                var ref = equipped.get();
                mc.setScreen(new ClairvoyanceScreen(ref.slot(), ref.index()));
                return;
            }
        }
        List<ItemStack> inv = player.getInventory().items;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).getItem() instanceof ClairvoyanceItem) {
                mc.setScreen(new ClairvoyanceScreen(i));
                return;
            }
        }
    }
}
