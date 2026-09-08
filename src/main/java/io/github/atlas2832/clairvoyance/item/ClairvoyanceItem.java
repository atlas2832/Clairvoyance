package io.github.atlas2832.clairvoyance.item;

import io.github.atlas2832.clairvoyance.client.ClientHandler;
import io.github.atlas2832.clairvoyance.compat.CuriosCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class ClairvoyanceItem extends Item {
    public ClairvoyanceItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                boolean next = flipActive(stack);
                player.displayClientMessage(toggledMessage(next), true);
            }
        } else {
            if (level.isClientSide) {
                ClientHandler.openScreen(hand);
            }
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        boolean active = stack.hasTag() && stack.getTag().getBoolean("Active");
        tooltip.add(Component.translatable(active
                ? "tooltip.clairvoyance.enabled"
                : "tooltip.clairvoyance.disabled").withStyle(active
                ? ChatFormatting.GREEN
                : ChatFormatting.RED));
        tooltip.add(Component.translatable("tooltip.clairvoyance.hint_gui")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.clairvoyance.hint_toggle")
                .withStyle(ChatFormatting.GRAY));
    }

    public static boolean isActive(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean("Active");
    }

    public static boolean flipActive(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        boolean next = !tag.getBoolean("Active");
        tag.putBoolean("Active", next);
        return next;
    }

    public static Component toggledMessage(boolean active) {
        return Component.translatable("message.clairvoyance.toggled",
                Component.translatable(active
                        ? "tooltip.clairvoyance.enabled"
                        : "tooltip.clairvoyance.disabled"));
    }

    // メインハンド→オフハンド→Curios→インベントリの順で探す
    public static ItemStack findFirst(Player player) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(player.getMainHandItem());
        stacks.add(player.getOffhandItem());
        if (CuriosCompat.isLoaded()) {
            CuriosCompat.addEquippedCurios(player, stacks);
        }
        stacks.addAll(player.getInventory().items);
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && stack.getItem() instanceof ClairvoyanceItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    // mode: 0=both 1=outline 2=fill color: ARGB
    public record TargetEntry(String id, boolean enabled, int mode, int color, int distance) {
    }

    public static int defaultColor() {
        return 0x4CFFFFFF;
    }

    public static int defaultDistance() {
        return 64;
    }

    public static String modeLangKey(int mode) {
        return switch (mode) {
            case 1 -> "mode.clairvoyance.outline";
            case 2 -> "mode.clairvoyance.fill";
            default -> "mode.clairvoyance.both";
        };
    }

    public static List<TargetEntry> getTargets(ItemStack stack) {
        List<TargetEntry> out = new ArrayList<>();
        if (!stack.hasTag()) return out;
        int fallbackColor = defaultColor();
        Tag tag = stack.getTag().get("Targets");
        if (!(tag instanceof ListTag list)) return out;
        for (Tag element : list) {
            if (element instanceof StringTag stringTag) {
                out.add(new TargetEntry(stringTag.getAsString(), true, 0, fallbackColor, defaultDistance()));
            } else if (element instanceof CompoundTag compound) {
                int mode = compound.contains("mode", Tag.TAG_INT)
                        ? Math.floorMod(compound.getInt("mode"), 3) : 0;
                int color = compound.contains("color", Tag.TAG_INT)
                        ? compound.getInt("color") : fallbackColor;
                int distance = compound.contains("distance", Tag.TAG_INT)
                        ? Math.max(1, compound.getInt("distance")) : defaultDistance();
                out.add(new TargetEntry(compound.getString("id"), compound.getBoolean("enabled"),
                        mode, color, distance));
            }
        }
        return out;
    }

    public static Map<String, TargetEntry> getEnabledTargets(ItemStack stack) {
        Map<String, TargetEntry> map = new LinkedHashMap<>();
        for (TargetEntry entry : getTargets(stack)) {
            if (entry.enabled()) map.putIfAbsent(entry.id(), entry);
        }
        return map;
    }

    private static void writeTargets(ItemStack stack, Map<String, TargetEntry> entries) {
        ListTag list = new ListTag();
        for (TargetEntry entry : entries.values()) {
            CompoundTag compound = new CompoundTag();
            compound.putString("id", entry.id());
            compound.putBoolean("enabled", entry.enabled());
            compound.putInt("mode", entry.mode());
            compound.putInt("color", entry.color());
            compound.putInt("distance", entry.distance());
            list.add(compound);
        }
        stack.getOrCreateTag().put("Targets", list);
    }

    private static Map<String, TargetEntry> readTargetsMap(ItemStack stack) {
        Map<String, TargetEntry> map = new LinkedHashMap<>();
        for (TargetEntry entry : getTargets(stack)) {
            map.putIfAbsent(entry.id(), entry);
        }
        return map;
    }

    public static void addTarget(ItemStack stack, String id, int color, int distance) {
        Map<String, TargetEntry> map = readTargetsMap(stack);
        map.putIfAbsent(id, new TargetEntry(id, true, 0, color, Math.max(1, distance)));
        writeTargets(stack, map);
    }

    public static void removeTarget(ItemStack stack, String id) {
        Map<String, TargetEntry> map = readTargetsMap(stack);
        map.remove(id);
        writeTargets(stack, map);
    }

    public static void toggleTarget(ItemStack stack, String id) {
        Map<String, TargetEntry> map = readTargetsMap(stack);
        map.computeIfPresent(id, (key, entry) ->
                new TargetEntry(id, !entry.enabled(), entry.mode(), entry.color(), entry.distance()));
        writeTargets(stack, map);
    }

    public static void cycleTargetMode(ItemStack stack, String id) {
        Map<String, TargetEntry> map = readTargetsMap(stack);
        TargetEntry entry = map.get(id);
        if (entry == null) return;
        int next = (entry.mode() + 1) % 3;
        map.put(id, new TargetEntry(id, entry.enabled(), next, entry.color(), entry.distance()));
        writeTargets(stack, map);
    }

    public static void setTargetStyle(ItemStack stack, String id, int color, int distance) {
        Map<String, TargetEntry> map = readTargetsMap(stack);
        map.computeIfPresent(id, (key, entry) ->
                new TargetEntry(id, entry.enabled(), entry.mode(), color, Math.max(1, distance)));
        writeTargets(stack, map);
    }
}
