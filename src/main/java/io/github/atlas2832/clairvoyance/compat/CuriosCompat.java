package io.github.atlas2832.clairvoyance.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotTypeMessage;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

public class CuriosCompat {
    public static final String MODID = "curios";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    public static void registerCurio(Item item) {
        CuriosApi.registerCurio(item, new ICurioItem() {
            @Override
            public boolean canEquip(SlotContext slotContext, ItemStack stack) {
                // 既に装備中なら多重装備を拒否
                LivingEntity entity = slotContext.entity();
                if (entity instanceof Player player) {
                    return CuriosApi.getCuriosInventory(player)
                            .map(handler -> handler.findCurios(item).isEmpty())
                            .orElse(true);
                }
                return true;
            }
        });
    }

    public static void enqueueSlots() {
        InterModComms.sendTo(MODID, SlotTypeMessage.REGISTER_TYPE,
                () -> new SlotTypeMessage.Builder("clairvoyance")
                        .size(1)
                        .icon(new ResourceLocation("clairvoyance", "slot/empty_clairvoyance_slot"))
                        .build());
    }

    public static void addEquippedCurios(Player player, List<ItemStack> out) {
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            IItemHandlerModifiable equipped = handler.getEquippedCurios();
            for (int i = 0; i < equipped.getSlots(); i++) {
                ItemStack stack = equipped.getStackInSlot(i);
                if (!stack.isEmpty()) out.add(stack);
            }
        });
    }

    public record CurioRef(String slot, int index, ItemStack stack) {
    }

    public static java.util.Optional<CurioRef> findFirstEquipped(Player player, Item item) {
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(handler -> handler.findFirstCurio(item))
                .map(result -> new CurioRef(result.slotContext().identifier(),
                        result.slotContext().index(), result.stack()));
    }

    public static ItemStack getStackInSlot(Player player, String slot, int index) {
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(handler -> handler.findCurio(slot, index))
                .map(top.theillusivec4.curios.api.SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }
}
