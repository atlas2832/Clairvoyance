package io.github.atlas2832.clairvoyance.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

// Curios導入時のみ読み込む 外部型の実装を含むのでCuriosCompatから分離
public class CuriosRegistration {
    public static void registerCurio(Item item) {
        CuriosApi.registerCurio(item, new ICurioItem() {
            @Override
            public boolean canEquip(SlotContext slotContext, ItemStack stack) {
                // 1個でも装備中なら多重装備を拒否
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
}
