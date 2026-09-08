package io.github.atlas2832.clairvoyance.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.atlas2832.clairvoyance.Config;
import io.github.atlas2832.clairvoyance.compat.CuriosCompat;
import io.github.atlas2832.clairvoyance.item.ClairvoyanceItem;
import io.github.atlas2832.clairvoyance.network.ClairvoyanceActionPacket;
import io.github.atlas2832.clairvoyance.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ClairvoyanceScreen extends Screen {
    private static final int PADDING = 10;
    private static final int BOTTOM_BAR_HEIGHT = 20;
    private static final int SEARCH_BAR_HEIGHT = 20;
    private static final int SECTION_GAP = 14;
    private static final int ROW_GAP = 6;
    private static final int ICON_SIZE = 16;
    private static final int ROW_HEIGHT = 18;

    private static int maxSuggestions() {
        return Config.MAX_SUGGESTIONS_SHOWN.get();
    }

    private int listScroll = 0;
    private int suggestionScroll = 0;
    private int suggestionSelectedIndex = 0;

    private final InteractionHand hand;
    // 0=手持ち、1=Curios、2=インベントリ
    private final int source;
    private final String curiosSlot;
    private final int slotIndex;

    private EditBox input;
    private EditBox boxColor;
    private EditBox boxDistance;
    private List<ResourceLocation> suggestions = List.of();
    private int selectedIndex = -1;
    private int lastColor = ClairvoyanceItem.defaultColor();
    private int lastDistance = ClairvoyanceItem.defaultDistance();

    private int panelLeft, panelRight;
    private int listTop, listBottom, searchY, bottomBarY, colorY, previewX;

    public ClairvoyanceScreen(InteractionHand hand) {
        this(hand, null, 0, 0);
    }

    public ClairvoyanceScreen(String curiosSlot, int curiosIndex) {
        this(InteractionHand.MAIN_HAND, curiosSlot, curiosIndex, 1);
    }

    public ClairvoyanceScreen(int inventoryIndex) {
        this(InteractionHand.MAIN_HAND, null, inventoryIndex, 2);
    }

    private ClairvoyanceScreen(InteractionHand hand, String curiosSlot, int slotIndex, int source) {
        super(Component.translatable("gui.clairvoyance.title"));
        this.hand = hand;
        this.curiosSlot = curiosSlot;
        this.slotIndex = slotIndex;
        this.source = source;
    }

    private ItemStack currentStack() {
        if (this.source == 1) {
            if (this.curiosSlot == null || !CuriosCompat.isLoaded()) return ItemStack.EMPTY;
            return CuriosCompat.getStackInSlot(Minecraft.getInstance().player, this.curiosSlot, this.slotIndex);
        }
        if (this.source == 2) {
            return Minecraft.getInstance().player.getInventory().getItem(this.slotIndex);
        }
        return Minecraft.getInstance().player.getItemInHand(this.hand);
    }

    private ClairvoyanceActionPacket packet(ClairvoyanceActionPacket.Action action,
                                            String param, int color, int distance) {
        if (this.source == 1 && this.curiosSlot != null) {
            return new ClairvoyanceActionPacket(action, param, this.hand, color, distance,
                    1, this.curiosSlot, this.slotIndex);
        }
        if (this.source == 2) {
            return new ClairvoyanceActionPacket(action, param, this.hand, color, distance,
                    2, "", this.slotIndex);
        }
        return new ClairvoyanceActionPacket(action, param, this.hand, color, distance);
    }

    private List<ClairvoyanceItem.TargetEntry> getTargets() {
        return ClairvoyanceItem.getTargets(currentStack());
    }

    @Override
    protected void init() {
        int panelWidth = (int) (this.width * Config.PANEL_WIDTH_RATIO.get());
        this.panelLeft = (this.width - panelWidth) / 2;
        this.panelRight = this.panelLeft + panelWidth;

        this.bottomBarY = this.height - PADDING - BOTTOM_BAR_HEIGHT;
        this.colorY = this.bottomBarY - ROW_GAP - SEARCH_BAR_HEIGHT;
        this.searchY = this.colorY - ROW_GAP - SEARCH_BAR_HEIGHT;
        this.listTop = PADDING + 14;
        this.listBottom = this.searchY - SECTION_GAP;

        this.input = new EditBox(this.font, panelLeft + PADDING, this.searchY, panelWidth - PADDING * 2 - 55, SEARCH_BAR_HEIGHT, Component.empty());
        this.input.setMaxLength(256);
        this.input.setResponder(this::onInputChanged);
        this.addRenderableWidget(this.input);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.clairvoyance.add"), b -> onAdd())
                .pos(panelRight - PADDING - 50, this.searchY)
                .size(50, SEARCH_BAR_HEIGHT)
                .build());

        int totalW = panelWidth - PADDING * 2;
        int applyW = 56;
        int previewS = 20;
        int distW = 44;
        int gap = 4;
        int boxW = totalW - distW - applyW - previewS - gap * 3;
        this.boxColor = new EditBox(this.font, panelLeft + PADDING, this.colorY, boxW,
                SEARCH_BAR_HEIGHT, Component.empty());
        this.boxColor.setMaxLength(9);
        this.boxColor.setFilter(value -> value.length() <= 9 && value.matches("#?[0-9a-fA-F]*"));
        this.boxColor.setSuggestion("#AARRGGBB");
        this.boxColor.setResponder(value -> this.boxColor.setSuggestion(value.isEmpty() ? "#AARRGGBB" : null));
        this.addRenderableWidget(this.boxColor);

        this.boxDistance = new EditBox(this.font, panelLeft + PADDING + boxW + gap, this.colorY,
                distW, SEARCH_BAR_HEIGHT, Component.empty());
        this.boxDistance.setMaxLength(4);
        this.boxDistance.setFilter(value -> value.length() <= 4 && value.matches("[0-9]*"));
        this.boxDistance.setSuggestion(Component.translatable("gui.clairvoyance.distance").getString());
        this.boxDistance.setResponder(value -> this.boxDistance.setSuggestion(
                value.isEmpty() ? Component.translatable("gui.clairvoyance.distance").getString() : null));
        this.addRenderableWidget(this.boxDistance);

        this.previewX = panelRight - PADDING - applyW - gap - previewS;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.clairvoyance.apply"), b -> onApplyStyle())
                .pos(panelRight - PADDING - applyW, this.colorY)
                .size(applyW, SEARCH_BAR_HEIGHT)
                .build());

        int btnWidth = (panelWidth - PADDING * 2 - 10 * 2) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.clairvoyance.mode"), b -> onCycleMode())
                .pos(panelLeft + PADDING, this.bottomBarY).size(btnWidth, BOTTOM_BAR_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.clairvoyance.mode.tooltip")))
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.clairvoyance.toggle"), b -> onToggle())
                .pos(panelLeft + PADDING + btnWidth + 10, this.bottomBarY).size(btnWidth, BOTTOM_BAR_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.clairvoyance.toggle.tooltip")))
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.clairvoyance.remove"), b -> onRemoveSelected())
                .pos(panelLeft + PADDING + (btnWidth + 10) * 2, this.bottomBarY).size(btnWidth, BOTTOM_BAR_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.clairvoyance.remove.tooltip")))
                .build());
    }

    private static List<ResourceLocation> allBlockIds = null;

    private void onInputChanged(String value) {
        if (value.isBlank()) {
            this.suggestions = List.of();
            return;
        }
        if (allBlockIds == null) {
            allBlockIds = ForgeRegistries.BLOCKS.getKeys().stream()
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .toList();
        }

        String lowerValue = value.toLowerCase(Locale.ROOT);
        this.suggestions = allBlockIds.stream()
                .filter(rl -> rl.toString().contains(lowerValue))
                .toList();

        this.suggestionScroll = 0;
        this.suggestionSelectedIndex = 0;
    }

    private void onAdd() {
        String value = this.input.getValue();
        ResourceLocation rl = ResourceLocation.tryParse(value);
        if (rl == null || !ForgeRegistries.BLOCKS.containsKey(rl)) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(packet(
                ClairvoyanceActionPacket.Action.ADD, rl.toString(),
                parseColorBox(), parseDistanceBox()));
        this.input.setValue("");
        this.suggestions = List.of();
    }

    private void onApplyStyle() {
        List<ClairvoyanceItem.TargetEntry> targets = getTargets();
        if (selectedIndex < 0 || selectedIndex >= targets.size()) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(packet(
                ClairvoyanceActionPacket.Action.APPLY, targets.get(selectedIndex).id(),
                parseColorBox(), parseDistanceBox()));
    }

    private int parseDistanceBox() {
        try {
            int value = Integer.parseInt(boxDistance.getValue().trim());
            lastDistance = Math.max(1, Math.min(Config.MAX_SCAN_DISTANCE.get(), value));
        } catch (NumberFormatException ignored) {
        }
        return lastDistance;
    }

    private void loadDistanceIntoBox(int distance) {
        boxDistance.setValue(String.valueOf(distance));
        lastDistance = distance;
    }

    private int parseColorBox() {
        String value = boxColor.getValue().trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            if (value.length() == 6) {
                lastColor = 0xFF000000 | Integer.parseUnsignedInt(value, 16);
            } else if (value.length() == 8) {
                lastColor = Integer.parseUnsignedInt(value, 16);
            }
        } catch (NumberFormatException ignored) {
        }
        return lastColor;
    }

    private void loadColorIntoBox(int argb) {
        boxColor.setValue(String.format("#%08X", argb));
        lastColor = argb;
    }

    private void onRemoveSelected() {
        List<ClairvoyanceItem.TargetEntry> targets = getTargets();
        if (selectedIndex < 0 || selectedIndex >= targets.size()) {
            return;
        }
        String value = targets.get(selectedIndex).id();
        NetworkHandler.CHANNEL.sendToServer(packet(
                ClairvoyanceActionPacket.Action.REMOVE, value, 0, 0));
        this.selectedIndex = -1;
    }

    private void onCycleMode() {
        List<ClairvoyanceItem.TargetEntry> targets = getTargets();
        if (selectedIndex < 0 || selectedIndex >= targets.size()) {
            return;
        }
        String value = targets.get(selectedIndex).id();
        NetworkHandler.CHANNEL.sendToServer(packet(
                ClairvoyanceActionPacket.Action.MODE, value, 0, 0));
    }

    private void onToggle() {
        List<ClairvoyanceItem.TargetEntry> targets = getTargets();
        if (selectedIndex < 0 || selectedIndex >= targets.size()) {
            return;
        }
        String value = targets.get(selectedIndex).id();
        NetworkHandler.CHANNEL.sendToServer(packet(
                ClairvoyanceActionPacket.Action.TOGGLE, value, 0, 0));
    }

    private void renderBlockIcon(GuiGraphics guiGraphics, Block block, int x, int y) {
        ItemStack itemStack = new ItemStack(block.asItem());

        if (!itemStack.isEmpty()) {
            guiGraphics.renderItem(itemStack, x, y);
            return;
        }

        BlockState state = block.defaultBlockState();
        FluidState fluidState = state.getFluidState();

        if (!fluidState.isEmpty()) {
            Fluid fluid = fluidState.getType();
            IClientFluidTypeExtensions fluidExt = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation textureLoc = fluidExt.getStillTexture();

            if (textureLoc != null) {
                TextureAtlasSprite sprite = Minecraft.getInstance()
                        .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                        .apply(textureLoc);

                int color = fluidExt.getTintColor();
                float a = ((color >> 24) & 0xFF) / 255.0f;
                float r = ((color >> 16) & 0xFF) / 255.0f;
                float g = ((color >> 8) & 0xFF) / 255.0f;
                float b = ((color) & 0xFF) / 255.0f;
                if (a == 0.0f) a = 1.0f;

                RenderSystem.setShaderColor(r, g, b, a);
                guiGraphics.blit(x, y, 0, 16, 16, sprite);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
            return;
        }

        if (state.getRenderShape() == RenderShape.MODEL) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();

            pose.translate(x + 8.0f, y + 8.0f, 150.0f);

            // マイクラのアイテムスケール(16 * 0.625 = 10.0f)に縮小
            pose.scale(10.0f, -10.0f, 10.0f);

            pose.mulPose(Axis.XP.rotationDegrees(30.0f));
            pose.mulPose(Axis.YP.rotationDegrees(225.0f));

            pose.translate(-0.5f, -0.5f, -0.5f);

            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
            // バニラの5引数版も内部でnullを渡すらしい
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    state, pose, bufferSource,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, null
            );

            bufferSource.endBatch();
            pose.popPose();
        }
    }

    private void renderTargetList(GuiGraphics guiGraphics) {
        List<ClairvoyanceItem.TargetEntry> targets = getTargets();

        int maxScroll = Math.max(0, targets.size() * ROW_HEIGHT - (listBottom - listTop));
        if (listScroll > maxScroll) {
            listScroll = maxScroll;
        }

        guiGraphics.enableScissor(panelLeft, listTop, panelRight, listBottom);

        int y = listTop - listScroll;

        for (int i = 0; i < targets.size(); i++) {
            if (y + ROW_HEIGHT > listTop && y < listBottom) {
                boolean selected = i == selectedIndex;
                ClairvoyanceItem.TargetEntry entry = targets.get(i);
                ResourceLocation rl = ResourceLocation.tryParse(entry.id());
                Block block = rl != null ? ForgeRegistries.BLOCKS.getValue(rl) : null;

                if (block != null) {
                    renderBlockIcon(guiGraphics, block, panelLeft + PADDING, y);
                }

                int textColor;
                if (!entry.enabled()) {
                    textColor = selected ? 0xBBBB44 : 0x888888;
                } else {
                    textColor = selected ? 0xFFFF55 : 0xDDDDDD;
                }
                String modeLabel = "["
                        + Component.translatable(ClairvoyanceItem.modeLangKey(entry.mode())).getString()
                        + " " + entry.distance() + "]";
                int modeW = this.font.width(modeLabel);
                int modeX = panelRight - PADDING - modeW;
                int idX = panelLeft + PADDING + ICON_SIZE + 4;
                String idStr = entry.id();
                int maxIdW = modeX - 4 - idX;
                if (this.font.width(idStr) > maxIdW && maxIdW > 12) {
                    idStr = this.font.plainSubstrByWidth(idStr, maxIdW - this.font.width("...")) + "...";
                }
                guiGraphics.drawString(this.font, idStr, idX, y + 4, textColor);
                guiGraphics.fill(modeX - 3, y + 2, panelRight - PADDING + 3, y + 16, 0x66000000);
                guiGraphics.drawString(this.font, modeLabel, modeX, y + 4,
                        !entry.enabled() ? 0x777777 : 0xAAAAAA);
            }
            y += ROW_HEIGHT;
        }

        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!suggestions.isEmpty()) {
            int shown = Math.min(suggestions.size(), maxSuggestions());
            int sugHeight = shown * 10 + 4;
            int sugY = searchY - sugHeight - 2;
            int sugRight = panelRight - PADDING - 55;

            if (mouseY >= sugY && mouseY < sugY + sugHeight && mouseX >= panelLeft + PADDING && mouseX <= sugRight) {
                int localIndex = (int) (mouseY - sugY - 2) / 10;
                int index = suggestionScroll + localIndex;

                if (index >= 0 && index < suggestions.size()) {
                    this.input.setValue(suggestions.get(index).toString());
                    this.suggestions = List.of();
                    this.suggestionScroll = 0;
                    return true;
                }
            }
        }

        if (mouseY >= listTop && mouseY < listBottom && mouseX >= panelLeft + PADDING && mouseX <= panelRight - PADDING) {
            int index = (int) ((mouseY - listTop + listScroll) / ROW_HEIGHT);
            List<ClairvoyanceItem.TargetEntry> targets = getTargets();

            if (index >= 0 && index < targets.size()) {
                this.selectedIndex = index;
                loadColorIntoBox(targets.get(index).color());
                loadDistanceIntoBox(targets.get(index).distance());
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!suggestions.isEmpty()) {
            int shown = Math.min(suggestions.size(), maxSuggestions());
            int sugHeight = shown * 10 + 4;
            int sugY = searchY - sugHeight - 2;
            int sugLeft = panelLeft + PADDING;
            int sugRight = panelRight - PADDING - 55;

            if (mouseX >= sugLeft && mouseX <= sugRight && mouseY >= sugY && mouseY < sugY + sugHeight) {
                int maxScroll = Math.max(0, suggestions.size() - maxSuggestions());
                suggestionScroll = Math.max(0, Math.min(maxScroll, suggestionScroll - (int) delta));
                return true;
            }
        }

        if (mouseY >= listTop && mouseY < listBottom && mouseX >= panelLeft + PADDING && mouseX <= panelRight - PADDING) {
            List<ClairvoyanceItem.TargetEntry> targets = getTargets();
            int maxScroll = Math.max(0, targets.size() * ROW_HEIGHT - (listBottom - listTop));
            listScroll = Math.max(0, Math.min(maxScroll, listScroll - (int) (delta * ROW_HEIGHT)));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // テキスト編集中はインベントリキーで閉じない
        boolean editing = this.input.isFocused() || this.boxColor.isFocused()
                || this.boxDistance.isFocused();
        if (!editing
                && this.minecraft.options.keyInventory.isActiveAndMatches(
                        InputConstants.getKey(keyCode, scanCode))) {
            this.minecraft.setScreen(null);
            return true;
        }

        if (!suggestions.isEmpty() && this.input.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                if (suggestionSelectedIndex > 0) {
                    suggestionSelectedIndex--;
                    if (suggestionSelectedIndex < suggestionScroll) {
                        suggestionScroll = suggestionSelectedIndex;
                    }
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                if (suggestionSelectedIndex < suggestions.size() - 1) {
                    suggestionSelectedIndex++;
                    if (suggestionSelectedIndex >= suggestionScroll + maxSuggestions()) {
                        suggestionScroll = suggestionSelectedIndex - maxSuggestions() + 1;
                    }
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                if (suggestionSelectedIndex >= 0 && suggestionSelectedIndex < suggestions.size()) {
                    this.input.setValue(suggestions.get(suggestionSelectedIndex).toString());
                    this.suggestions = List.of();
                }
                return true;
            }
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.input.isFocused()) {
            onAdd();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.fill(panelLeft, 0, panelRight, this.height, 0x99000000);
        renderTargetList(guiGraphics);
        int previewColor = parseColorBox();
        guiGraphics.fill(previewX - 1, colorY - 1, previewX + 21, colorY + 21, 0xFF555555);
        guiGraphics.fill(previewX, colorY, previewX + 20, colorY + 20, previewColor);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (!suggestions.isEmpty()) {
            int suggestionEnd = Math.min(suggestionScroll + maxSuggestions(), suggestions.size());
            int shown = Math.min(suggestions.size(), maxSuggestions());
            int sugHeight = shown * 10 + 4;
            int sugY = searchY - sugHeight - 2;
            int sugRight = panelRight - PADDING - 55;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 250.0f);

            guiGraphics.fill(panelLeft + PADDING - 1, sugY - 1, sugRight + 1, sugY + sugHeight + 1, 0xEE555555);

            guiGraphics.fill(panelLeft + PADDING, sugY, sugRight, sugY + sugHeight, 0xEE111111);

            guiGraphics.enableScissor(panelLeft + PADDING, sugY, sugRight, sugY + sugHeight);

            for (int i = suggestionScroll; i < suggestionEnd; i++) {
                int localIndex = i - suggestionScroll;
                boolean isSelected = (i == suggestionSelectedIndex);

                guiGraphics.drawString(
                        this.font,
                        suggestions.get(i).toString(),
                        panelLeft + PADDING + 2,
                        sugY + 2 + localIndex * 10,
                        isSelected ? 0xFFFF55 : 0xAAAAAA
                );
            }

            guiGraphics.disableScissor();

            guiGraphics.pose().popPose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}