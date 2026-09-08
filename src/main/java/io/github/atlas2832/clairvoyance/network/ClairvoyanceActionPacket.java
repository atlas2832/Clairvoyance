package io.github.atlas2832.clairvoyance.network;

import io.github.atlas2832.clairvoyance.compat.CuriosCompat;
import io.github.atlas2832.clairvoyance.item.ClairvoyanceItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

public class ClairvoyanceActionPacket {
    public enum Action { ADD, REMOVE, MODE, TOGGLE, APPLY, ITEM_TOGGLE }

    private final Action action;
    private final String param;
    private final InteractionHand hand;
    private final int color;
    private final int distance;
    private final int source;   // 0=手持ち、1=Curios、2=インベントリ番号
    private final String slotId;
    private final int slotIndex;

    public ClairvoyanceActionPacket(Action action, String param, InteractionHand hand) {
        this(action, param, hand, 0, 0);
    }

    public ClairvoyanceActionPacket(Action action, String param, InteractionHand hand, int color, int distance) {
        this(action, param, hand, color, distance, 0, "", 0);
    }

    public ClairvoyanceActionPacket(Action action, String param, InteractionHand hand,
                                    int color, int distance, int source, String slotId, int slotIndex) {
        this.action = action;
        this.param = param;
        this.hand = hand;
        this.color = color;
        this.distance = distance;
        this.source = source;
        this.slotId = slotId == null ? "" : slotId;
        this.slotIndex = slotIndex;
    }

    public static void encode(ClairvoyanceActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeUtf(msg.param == null ? "" : msg.param);
        buf.writeEnum(msg.hand);
        buf.writeInt(msg.color);
        buf.writeInt(msg.distance);
        buf.writeInt(msg.source);
        buf.writeUtf(msg.slotId);
        buf.writeInt(msg.slotIndex);
    }

    public static ClairvoyanceActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        String param = buf.readUtf();
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        int color = buf.readInt();
        int distance = buf.readInt();
        int source = buf.readInt();
        String slotId = buf.readUtf();
        int slotIndex = buf.readInt();
        return new ClairvoyanceActionPacket(action, param, hand, color, distance, source, slotId, slotIndex);
    }

    public static void handle(ClairvoyanceActionPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            if (msg.action == Action.ITEM_TOGGLE) {
                ItemStack found = ClairvoyanceItem.findFirst(player);
                if (!found.isEmpty()) {
                    boolean next = ClairvoyanceItem.flipActive(found);
                    player.displayClientMessage(ClairvoyanceItem.toggledMessage(next), true);
                }
                return;
            }

            ItemStack stack;
            if (msg.source == 1 && CuriosCompat.isLoaded()) {
                stack = CuriosCompat.getStackInSlot(player, msg.slotId, msg.slotIndex);
            } else if (msg.source == 2) {
                stack = player.getInventory().getItem(msg.slotIndex);
            } else {
                stack = player.getItemInHand(msg.hand);
            }
            if (!(stack.getItem() instanceof ClairvoyanceItem)) return;

            switch (msg.action) {
                case ADD -> {
                    ResourceLocation rl = ResourceLocation.tryParse(msg.param);
                    if (rl != null && ForgeRegistries.BLOCKS.containsKey(rl)) {
                        ClairvoyanceItem.addTarget(stack, rl.toString(), msg.color, msg.distance);
                    }
                }
                case APPLY -> {
                    if (!msg.param.isEmpty()) {
                        ClairvoyanceItem.setTargetStyle(stack, msg.param, msg.color, msg.distance);
                    }
                }
                case REMOVE -> ClairvoyanceItem.removeTarget(stack, msg.param);
                case MODE -> {
                    if (!msg.param.isEmpty()) {
                        ClairvoyanceItem.cycleTargetMode(stack, msg.param);
                    }
                }
                case TOGGLE -> {
                    if (!msg.param.isEmpty()) {
                        ClairvoyanceItem.toggleTarget(stack, msg.param);
                    }
                }
                default -> {
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
