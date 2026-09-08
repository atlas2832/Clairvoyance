package io.github.atlas2832.clairvoyance;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Clairvoyance.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue PANEL_WIDTH_RATIO = BUILDER
            .comment("GUI panel width as a ratio of screen width")
            .translation("clairvoyance.configuration.panelWidthRatio")
            .defineInRange("panelWidthRatio", 0.4, 0.1, 1.0);

    public static final ForgeConfigSpec.IntValue MAX_SUGGESTIONS_SHOWN = BUILDER
            .comment("Number of search suggestions shown at once")
            .translation("clairvoyance.configuration.maxSuggestionsShown")
            .defineInRange("maxSuggestionsShown", 10, 1, 50);

    public static final ForgeConfigSpec.IntValue MAX_SCAN_DISTANCE = BUILDER
            .comment("Upper limit for the per-block display distance")
            .translation("clairvoyance.configuration.maxScanDistance")
            .defineInRange("maxScanDistance", 256, 1, 1024);

    public static final ForgeConfigSpec.DoubleValue OUTLINE_WIDTH = BUILDER
            .comment("Outline thickness (px)")
            .translation("clairvoyance.configuration.outlineWidth")
            .defineInRange("outlineWidth", 2.0, 0.5, 10.0);

    public static final ForgeConfigSpec.IntValue SCAN_INTERVAL_TICKS = BUILDER
            .comment("Tracking verification and render batch interval (ticks)")
            .translation("clairvoyance.configuration.scanIntervalTicks")
            .defineInRange("scanIntervalTicks", 2, 1, 20);

    static final ForgeConfigSpec SPEC = BUILDER.build();
}