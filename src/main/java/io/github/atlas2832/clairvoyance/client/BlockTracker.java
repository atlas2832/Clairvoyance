package io.github.atlas2832.clairvoyance.client;

import io.github.atlas2832.clairvoyance.Config;
import io.github.atlas2832.clairvoyance.compat.CuriosCompat;
import io.github.atlas2832.clairvoyance.item.ClairvoyanceItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

@Mod.EventBusSubscriber(modid = "clairvoyance", bus = Mod.EventBusSubscriber.Bus.FORGE, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public class BlockTracker {
    private record Style(int mode, int color, int distance) {
    }

    private static int clampDistance(int distance) {
        return Math.max(1, Math.min(Config.MAX_SCAN_DISTANCE.get(), distance));
    }

    private static final Map<Long, Style> trackedBlocks = new ConcurrentHashMap<>();

    private static boolean tracking = false;
    private static Map<Block, Style> activeTargets = Map.of();
    private static int activeTargetsHash = 0;

    public static int getDistance(long posLong, int fallback) {
        Style style = trackedBlocks.get(posLong);
        return style == null ? fallback : style.distance();
    }

    private static int maxActiveDistance() {
        int max = 0;
        for (Style style : activeTargets.values()) {
            if (style.distance() > max) max = style.distance();
        }
        return clampDistance(max);
    }

    private static int tick = 0;
    private static BlockPos lastScanCenter = null;
    private static int lastRescanTick = 0;

    private static final Set<Long> scannedChunks = new HashSet<>();
    private static final ArrayDeque<Long> scanQueue = new ArrayDeque<>();

    // 仮実装: 並行読みは非保証、外れ値はverifyで消える
    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Clairvoyance-Scanner");
        thread.setDaemon(true);
        return thread;
    });
    private record ScanResult(long generation, boolean hasData, long chunkLong, List<Long> found) {
    }

    private static final ConcurrentLinkedQueue<ScanResult> pendingMerges = new ConcurrentLinkedQueue<>();

    private static long scanGeneration = 0;

    // クリック確認待ち (別スレッドでも飛ぶのでconcurrent)
    private static final Map<Long, Integer> pendingChecks = new ConcurrentHashMap<>();

    // 使い回し座標 (tick専用)
    private static final BlockPos.MutableBlockPos SHARED_POS = new BlockPos.MutableBlockPos();

    public static boolean isTracking() {
        return tracking;
    }

    private static final Map<Integer, List<Long>> renderFillGroups = new LinkedHashMap<>();
    private static final Map<Integer, List<Long>> renderLineGroups = new LinkedHashMap<>();

    public static Map<Integer, List<Long>> fillGroups() {
        return renderFillGroups;
    }

    public static Map<Integer, List<Long>> lineGroups() {
        return renderLineGroups;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            tracking = false;
            trackedBlocks.clear();
            pendingChecks.clear();
            scannedChunks.clear();
            scanQueue.clear();
            renderFillGroups.clear();
            renderLineGroups.clear();
            return;
        }
        tick++;

        refreshActiveTargets(mc.player);

        if (!tracking) {
            if (!trackedBlocks.isEmpty()) trackedBlocks.clear();
            pendingChecks.clear();
            scannedChunks.clear();
            scanQueue.clear();
            renderFillGroups.clear();
            renderLineGroups.clear();
            lastScanCenter = null;
            return;
        }

        processPending(mc);

        BlockPos playerPos = mc.player.blockPosition();
        // 小規模は毎tick、大規模だけ間引き
        int interval = Math.max(1, Config.SCAN_INTERVAL_TICKS.get());
        if (tick % interval == 0) {
            verifyTracked(mc.level, playerPos);
            rebuildRenderBatch();
        } else if (trackedBlocks.size() <= 8192) {
            rebuildRenderBatch();
        }

        // 設定変更=作り直し 移動・10秒経過=積み直し
        boolean targetsChanged = activeTargetsHash != lastTargetsHash();
        boolean moved = lastScanCenter == null || playerPos.distSqr(lastScanCenter) >= 64;
        boolean stale = tick - lastRescanTick > 200;
        if (targetsChanged) {
            requestRescan(mc, playerPos, true);
        } else if (moved || stale) {
            requestRescan(mc, playerPos, false);
        }

        processScanQueue(mc, playerPos);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        trackedBlocks.clear();
        pendingChecks.clear();
        scannedChunks.clear();
        scanQueue.clear();
        renderFillGroups.clear();
        renderLineGroups.clear();
        tracking = false;
        lastScanCenter = null;
        // GLリソースはセッション中使い回すため破棄しない
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!tracking) return;
        if (event.getLevel().isClientSide) {
            queueCheck(event.getPos().asLong(), 3);
        }
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!tracking) return;
        if (!event.getLevel().isClientSide) return;
        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!(held.getItem() instanceof BlockItem)) return;
        Direction face = event.getFace();
        if (face == null) return;
        BlockPos placePos = event.getPos().relative(face);
        queueCheck(placePos.asLong(), 2);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // 別スレッド対策でtick委譲
        long chunkLong = event.getChunk().getPos().toLong();
        boolean clientSide = event.getLevel().isClientSide();
        Minecraft.getInstance().execute(() -> onChunkLoadClient(chunkLong, clientSide));
    }

    private static void onChunkLoadClient(long chunkLong, boolean clientSide) {
        if (!tracking || activeTargets.isEmpty()) return;
        if (!clientSide) return;
        if (scannedChunks.contains(chunkLong) || scanQueue.contains(chunkLong)) return;
        // 箱の外はrequestRescanに任せる
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            BlockPos p = mc.player.blockPosition();
            int r = maxActiveDistance() + 16;
            double dx = ChunkPos.getX(chunkLong) * 16 + 8 - (p.getX() + 0.5);
            double dz = ChunkPos.getZ(chunkLong) * 16 + 8 - (p.getZ() + 0.5);
            if (dx * dx + dz * dz > (double) r * r) return;
        }
        scanQueue.addFirst(chunkLong);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        // 別スレッド対策でtick委譲
        long chunkLong = event.getChunk().getPos().toLong();
        int chunkX = event.getChunk().getPos().x;
        int chunkZ = event.getChunk().getPos().z;
        Minecraft.getInstance().execute(() -> {
            scannedChunks.remove(chunkLong);
            scanQueue.remove(chunkLong);
            if (trackedBlocks.isEmpty()) return;
            trackedBlocks.keySet().removeIf(posLong -> {
                int x = (int) (posLong >> 38);
                int z = (int) (posLong << 26 >> 38);
                return (x >> 4) == chunkX && (z >> 4) == chunkZ;
            });
        });
    }

    private static void queueCheck(long posLong, int delayTicks) {
        if (pendingChecks.size() > 128) pendingChecks.clear();
        pendingChecks.put(posLong, tick + delayTicks);
    }

    private static void processPending(Minecraft mc) {
        if (pendingChecks.isEmpty()) return;
        ClientLevel level = mc.level;
        List<Long> due = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : pendingChecks.entrySet()) {
            if (tick >= e.getValue()) due.add(e.getKey());
        }
        for (long posLong : due) {
            pendingChecks.remove(posLong);
            int x = (int) (posLong >> 38);
            int y = (int) (posLong << 52 >> 52);
            int z = (int) (posLong << 26 >> 38);
            BlockState state = level.getBlockState(SHARED_POS.set(x, y, z));
            Style style = state.isAir() ? null : activeTargets.get(state.getBlock());
            if (style == null) {
                trackedBlocks.remove(posLong);
            } else {
                trackedBlocks.put(posLong, style);
            }
        }
    }

    private static void rebuildRenderBatch() {
        renderFillGroups.clear();
        renderLineGroups.clear();
        if (trackedBlocks.isEmpty() || !tracking) return;
        net.minecraft.world.phys.Vec3 center = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        for (Map.Entry<Long, Style> e : trackedBlocks.entrySet()) {
            long posLong = e.getKey();
            Style style = e.getValue();
            int x = (int) (posLong >> 38);
            int y = (int) (posLong << 52 >> 52);
            int z = (int) (posLong << 26 >> 38);
            double dx = x + 0.5 - center.x;
            double dy = y + 0.5 - center.y;
            double dz = z + 0.5 - center.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            double rr = style.distance();
            if (d2 > rr * rr) continue;
            if (style.mode() == 0 || style.mode() == 2) {
                renderFillGroups.computeIfAbsent(style.color(), key -> new ArrayList<>()).add(posLong);
            }
            if (style.mode() == 0 || style.mode() == 1) {
                renderLineGroups.computeIfAbsent(style.color(), key -> new ArrayList<>()).add(posLong);
            }
        }
    }

    private static void verifyTracked(ClientLevel level, BlockPos playerPos) {
        if (trackedBlocks.isEmpty()) return;
        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + 0.5;
        double pz = playerPos.getZ() + 0.5;
        for (Map.Entry<Long, Style> entry : trackedBlocks.entrySet()) {
            long posLong = entry.getKey();
            int x = (int) (posLong >> 38);
            int y = (int) (posLong << 52 >> 52);
            int z = (int) (posLong << 26 >> 38);
            BlockState state = level.getBlockState(SHARED_POS.set(x, y, z));
            Style style = state.isAir() ? null : activeTargets.get(state.getBlock());
            if (style == null) {
                trackedBlocks.remove(posLong);
                continue;
            }
            double dx = x + 0.5 - px;
            double dy = y + 0.5 - py;
            double dz = z + 0.5 - pz;
            double radiusSq = (double) style.distance() * style.distance();
            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                trackedBlocks.remove(posLong);
            } else if (!style.equals(entry.getValue())) {
                trackedBlocks.put(posLong, style);
            }
        }
    }

    private static int lastTargetsHash = 0;

    private static int lastTargetsHash() {
        return lastTargetsHash;
    }

    private static void refreshActiveTargets(Player player) {
        Map<String, Style> idStyles = new LinkedHashMap<>();
        boolean anyActive = false;

        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(player.getMainHandItem());
        stacks.add(player.getOffhandItem());
        if (CuriosCompat.isLoaded()) {
            CuriosCompat.addEquippedCurios(player, stacks);
        }
        stacks.addAll(player.getInventory().items);

        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !(stack.getItem() instanceof ClairvoyanceItem)) continue;
            if (!ClairvoyanceItem.isActive(stack)) continue;
            anyActive = true;
            for (ClairvoyanceItem.TargetEntry e : ClairvoyanceItem.getEnabledTargets(stack).values()) {
                idStyles.putIfAbsent(e.id(), new Style(e.mode(), e.color(), clampDistance(e.distance())));
            }
        }

        tracking = anyActive;
        if (!anyActive) {
            activeTargets = Map.of();
            activeTargetsHash = 0;
            return;
        }
        activeTargetsHash = idStyles.hashCode();
        if (lastTargetsHash == activeTargetsHash && !activeTargets.isEmpty()) return;
        activeTargets = resolveBlocks(idStyles);
    }

    private static void requestRescan(Minecraft mc, BlockPos playerPos, boolean rebuildAll) {
        ClientLevel level = mc.level;
        int r = maxActiveDistance();
        lastScanCenter = playerPos.immutable();
        lastTargetsHash = activeTargetsHash;
        lastRescanTick = tick;
        if (activeTargets.isEmpty()) {
            trackedBlocks.clear();
            scannedChunks.clear();
            scanQueue.clear();
            renderFillGroups.clear();
            renderLineGroups.clear();
            return;
        }

        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + 0.5;
        double pz = playerPos.getZ() + 0.5;
        if (rebuildAll) {
            trackedBlocks.clear();
            scannedChunks.clear();
            scanQueue.clear();
            pendingMerges.clear();
            scanGeneration++;
            renderFillGroups.clear();
            renderLineGroups.clear();
        } else {
            double rangeSq = (double) (r + 16) * (r + 16);
            trackedBlocks.keySet().removeIf(posLong -> {
                int x = (int) (posLong >> 38);
                int z = (int) (posLong << 26 >> 38);
                double dx = x + 0.5 - px;
                double dz = z + 0.5 - pz;
                return dx * dx + dz * dz > rangeSq;
            });
            scannedChunks.removeIf(chunkLong -> {
                int cx = ChunkPos.getX(chunkLong);
                int cz = ChunkPos.getZ(chunkLong);
                double dx = cx * 16 + 8 - px;
                double dz = cz * 16 + 8 - pz;
                return dx * dx + dz * dz > rangeSq;
            });
        }

        double[] centerNow = {px, py, pz};
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;
        for (int cx = pcx - 1; cx <= pcx + 1; cx++) {
            for (int cz = pcz - 1; cz <= pcz + 1; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                long chunkLong = ChunkPos.asLong(cx, cz);
                if (scanChunk(level.getChunk(cx, cz), activeTargets, centerNow,
                        trackedBlocks::put)) {
                    scannedChunks.add(chunkLong);
                }
                scanQueue.remove(chunkLong);
            }
        }

        List<long[]> columns = new ArrayList<>();
        int minCx = (playerPos.getX() - r) >> 4;
        int maxCx = (playerPos.getX() + r) >> 4;
        int minCz = (playerPos.getZ() - r) >> 4;
        int maxCz = (playerPos.getZ() + r) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                long chunkLong = ChunkPos.asLong(cx, cz);
                if (scanQueue.contains(chunkLong)) continue;
                double dx = cx * 16 + 8 - px;
                double dz = cz * 16 + 8 - pz;
                columns.add(new long[]{chunkLong, (long) (dx * dx + dz * dz)});
            }
        }
        columns.sort(Comparator.comparingLong(a -> a[1]));
        for (long[] column : columns) {
            scanQueue.addLast(column[0]);
        }
    }

    private static void processScanQueue(Minecraft mc, BlockPos playerPos) {
        mergeScanResults(mc, playerPos);
        if (scanQueue.isEmpty()) return;
        ClientLevel level = mc.level;
        if (level == null || !tracking) {
            scanQueue.clear();
            return;
        }
        double[] center = {playerPos.getX() + 0.5, playerPos.getY() + 0.5, playerPos.getZ() + 0.5};
        double far = maxActiveDistance() + 32;
        double farSq = far * far;
        Map<Block, Style> targets = activeTargets;
        long generation = scanGeneration;
        int submitted = 0;
        while (!scanQueue.isEmpty() && submitted < 64) {
            long chunkLong = scanQueue.pollFirst();
            int cx = ChunkPos.getX(chunkLong);
            int cz = ChunkPos.getZ(chunkLong);
            double dx = cx * 16 + 8 - center[0];
            double dz = cz * 16 + 8 - center[2];
            if (dx * dx + dz * dz > farSq) continue;
            if (!level.hasChunk(cx, cz)) continue;
            ChunkAccess chunk = level.getChunk(cx, cz);
            SCANNER.execute(() -> {
                try {
                    List<Long> found = new ArrayList<>();
                    boolean hasData = scanChunk(chunk, targets, center,
                            (posLong, style) -> found.add(posLong));
                    pendingMerges.add(new ScanResult(generation, hasData, chunkLong, found));
                } catch (Throwable ignored) {
                    pendingMerges.add(new ScanResult(generation, false, chunkLong, List.of()));
                }
            });
            submitted++;
        }
    }

    private static void mergeScanResults(Minecraft mc, BlockPos playerPos) {
        if (pendingMerges.isEmpty()) return;
        ClientLevel level = mc.level;
        if (level == null) {
            pendingMerges.clear();
            return;
        }
        double px = playerPos.getX() + 0.5;
        double py = playerPos.getY() + 0.5;
        double pz = playerPos.getZ() + 0.5;
        ScanResult result;
        while ((result = pendingMerges.poll()) != null) {
            if (result.generation() != scanGeneration) continue;
            if (!result.hasData()) continue;
            scannedChunks.add(result.chunkLong());
            for (long posLong : result.found()) {
                int x = (int) (posLong >> 38);
                int y = (int) (posLong << 52 >> 52);
                int z = (int) (posLong << 26 >> 38);
                BlockState state = level.getBlockState(SHARED_POS.set(x, y, z));
                Style style = state.isAir() ? null : activeTargets.get(state.getBlock());
                if (style == null) continue;
                double dx = x + 0.5 - px;
                double dy = y + 0.5 - py;
                double dz = z + 0.5 - pz;
                double radiusSq = (double) style.distance() * style.distance();
                if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                trackedBlocks.put(posLong, style);
            }
        }
    }

    /** 空チャンクはfalse ヒットはsinkへ */
    private static boolean scanChunk(ChunkAccess chunk, Map<Block, Style> targets, double[] center,
                                     BiConsumer<Long, Style> sink) {
        if (targets.isEmpty()) return true;
        boolean hasData = false;
        Predicate<BlockState> match = state -> targets.containsKey(state.getBlock());
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) continue;
            hasData = true;
            if (!section.maybeHas(match)) continue;
            int baseY = chunk.getMinBuildHeight() + si * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        Style style = targets.get(state.getBlock());
                        if (style == null) continue;
                        int wx = baseX + x;
                        int wy = baseY + y;
                        int wz = baseZ + z;
                        if (center != null) {
                            double dx = wx + 0.5 - center[0];
                            double dy = wy + 0.5 - center[1];
                            double dz = wz + 0.5 - center[2];
                            double radiusSq = (double) style.distance() * style.distance();
                            if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
                        }
                        sink.accept(BlockPos.asLong(wx, wy, wz), style);
                    }
                }
            }
        }
        return hasData;
    }

    private static Map<Block, Style> resolveBlocks(Map<String, Style> targetStyles) {
        Map<Block, Style> map = new HashMap<>();
        for (Map.Entry<String, Style> e : targetStyles.entrySet()) {
            try {
                Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(e.getKey()));
                if (block != null) map.putIfAbsent(block, e.getValue());
            } catch (Exception ignored) {
            }
        }
        return map;
    }

}
