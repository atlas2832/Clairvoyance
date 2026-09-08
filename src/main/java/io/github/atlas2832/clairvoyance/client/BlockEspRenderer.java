package io.github.atlas2832.clairvoyance.client;

import io.github.atlas2832.clairvoyance.Clairvoyance;
import io.github.atlas2832.clairvoyance.Config;
import io.github.atlas2832.clairvoyance.client.render.BlockOverlayRenderer;
import io.github.atlas2832.clairvoyance.client.render.InstancedCubeLineRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Clairvoyance.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class BlockEspRenderer {
    private static final InstancedCubeLineRenderer LINE_RENDERER = new InstancedCubeLineRenderer();
    private static final BlockOverlayRenderer OVERLAY_RENDERER = new BlockOverlayRenderer();

    private static int unpackX(long posLong) {
        return (int) (posLong >> 38);
    }

    private static int unpackY(long posLong) {
        return (int) (posLong << 52 >> 52);
    }

    private static int unpackZ(long posLong) {
        return (int) (posLong << 26 >> 38);
    }

    private static boolean inRange(long posLong, double cx, double cy, double cz) {
        int distance = BlockTracker.getDistance(posLong, 0);
        if (distance <= 0) return false;
        int x = unpackX(posLong);
        int y = unpackY(posLong);
        int z = unpackZ(posLong);
        double dx = x + 0.5 - cx;
        double dy = y + 0.5 - cy;
        double dz = z + 0.5 - cz;
        return dx * dx + dy * dy + dz * dz <= (double) distance * distance;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!BlockTracker.isTracking()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Map<Integer, List<Long>> fillGroups = BlockTracker.fillGroups();
        Map<Integer, List<Long>> lineGroups = BlockTracker.lineGroups();
        if (fillGroups.isEmpty() && lineGroups.isEmpty()) return;

        Vec3 cam = event.getCamera().getPosition();

        Matrix4f mvp = new Matrix4f(event.getProjectionMatrix())
                .mul(event.getPoseStack().last().pose());

        for (Map.Entry<Integer, List<Long>> group : fillGroups.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            OVERLAY_RENDERER.begin(cam);
            for (long posLong : group.getValue()) {
                if (!inRange(posLong, cam.x, cam.y, cam.z)) continue;
                OVERLAY_RENDERER.block(unpackX(posLong), unpackY(posLong), unpackZ(posLong));
            }
            OVERLAY_RENDERER.end(new Color(group.getKey(), true), mvp);
        }

        float width = Config.OUTLINE_WIDTH.get().floatValue();
        for (Map.Entry<Integer, List<Long>> group : lineGroups.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            Color outlineColor = new Color(group.getKey(), true);
            LINE_RENDERER.begin();
            for (long posLong : group.getValue()) {
                if (!inRange(posLong, cam.x, cam.y, cam.z)) continue;
                int x = unpackX(posLong);
                int y = unpackY(posLong);
                int z = unpackZ(posLong);
                LINE_RENDERER.cube(
                        (float) (x - cam.x),
                        (float) (y - cam.y),
                        (float) (z - cam.z),
                        outlineColor, width);
            }
            LINE_RENDERER.end(mvp);
        }
    }
}
