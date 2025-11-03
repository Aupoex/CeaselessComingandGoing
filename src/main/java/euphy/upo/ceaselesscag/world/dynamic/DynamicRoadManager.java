package euphy.upo.ceaselesscag.world.dynamic;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.architect.ArchitectManager;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;
import euphy.upo.ceaselesscag.world.terrain.TerrainHeightCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import euphy.upo.ceaselesscag.poi.PoiRegistry;
import euphy.upo.ceaselesscag.world.data.RoadNetworkData;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import euphy.upo.ceaselesscag.world.architect.RoadNetworkPlanner;
import euphy.upo.ceaselesscag.world.data.RoadSegment;
import java.util.concurrent.CompletableFuture;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 动态道路管理器
 * JIT架构核心。协调所有实时的后台任务。
 * 启动和管理后台线程，使用 TerrainHeightCalculator 计算玩家周围的地形，并存入 TerrainDataManager。
 *启动和管理后台线程，扫描玩家周围的 POI，并存入 RoadNetworkData。
 * 检查玩家位置，并将新任务分配给后台线程。
 */
public class DynamicRoadManager {

    /**
     * JIT 检查玩家位置的间隔 。
     */
    private static final int JIT_TICK_INTERVAL = 20;

    /**
     * JIT 地形缓存半径 (区块)。
     * 将在玩家周围半径的圆形区域内实时缓存地形。
     */
    private static final int JIT_TERRAIN_CACHE_RADIUS_CHUNKS = 60;

    /**
     * JIT POI 扫描半径 (区块)。
     * 将在玩家周围半径的圆形区域内实时扫描 POI。
     */
    private static final int JIT_POI_SCAN_RADIUS_CHUNKS = 60;

    /**
     * JIT POI 扫描速率 (区块数/游戏刻)。
     */
    private static final int POI_SCAN_RATE_PER_TICK = 100;

    /**
     * JIT 地形缓存的任务队列。
     */
    private final PriorityBlockingQueue<JitTask> terrainCacheQueue = new PriorityBlockingQueue<>();

    private final Set<Long> pendingTerrainChunks = ConcurrentHashMap.newKeySet();

    private ExecutorService poiScanExecutor;

    private final PriorityBlockingQueue<JitTask> poiScanQueue = new PriorityBlockingQueue<>();

    private final Set<Long> pendingPoiScanChunks = ConcurrentHashMap.newKeySet();

    /**
     * 专用于路径规划的后台线程池。
     */
    private ExecutorService pathPlanningExecutor;

    public static final DynamicRoadManager INSTANCE = new DynamicRoadManager();

    private ExecutorService terrainCacheExecutor;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private int tickCounter = 0;

    private DynamicRoadManager() {}

    public void start() {
        CeaselessCaG.LOGGER.info("[DynamicManager] 启动 JIT 管理器...");
        this.isShutdown.set(false);
        this.tickCounter = 0;

        this.terrainCacheQueue.clear();
        this.pendingTerrainChunks.clear();
        this.poiScanQueue.clear();
        this.pendingPoiScanChunks.clear();

        int terrainWorkerThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        if (this.terrainCacheExecutor == null || this.terrainCacheExecutor.isShutdown()) {
            this.terrainCacheExecutor = Executors.newFixedThreadPool(terrainWorkerThreads, r -> {
                Thread t = new Thread(r, "CeaselessCaG-JIT-Terrain-Worker");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            CeaselessCaG.LOGGER.info("[DynamicManager] 地形缓存线程池已启动 ({} 个工作线程)。", terrainWorkerThreads);

            for (int i = 0; i < terrainWorkerThreads; i++) {
                this.terrainCacheExecutor.submit(this::terrainCacheWorkerLoop);
            }
        }

        if (POI_SCAN_RATE_PER_TICK > 0) {
            if (this.poiScanExecutor == null || this.poiScanExecutor.isShutdown()) {
                this.poiScanExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "CeaselessCaG-JIT-POI-Worker");
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
                CeaselessCaG.LOGGER.info("[DynamicManager] POI Scan 线程池已启动 (1 个工作线程)。");
                this.poiScanExecutor.submit(this::poiScanWorkerLoop);
            }
        } else {
            CeaselessCaG.LOGGER.warn("[DynamicManager] JIT POI 扫描已禁用 (POI_SCAN_RATE_PER_TICK = 0)。");
        }

        if (this.pathPlanningExecutor == null || this.pathPlanningExecutor.isShutdown()) {
            this.pathPlanningExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CeaselessCaG-JIT-Planning-Worker");
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });
            CeaselessCaG.LOGGER.info("[DynamicManager] 路径规划 (Path Planning) 线程池已启动 (1 个工作线程)。");
        }
    }


    public void shutdown() {
        if (!this.isShutdown.compareAndSet(false, true)) {
            CeaselessCaG.LOGGER.warn("[DynamicManager] 已在关闭中，跳过。");
            return;
        }

        CeaselessCaG.LOGGER.info("[DynamicManager] 关闭 JIT 管理器...");

        this.terrainCacheQueue.clear();
        this.poiScanQueue.clear();

        shutdownExecutorService(this.pathPlanningExecutor, "JIT-Path-Planning");
        shutdownExecutorService(this.poiScanExecutor, "JIT-POI-Scan");
        shutdownExecutorService(this.terrainCacheExecutor, "JIT-Terrain-Cache");

        this.pathPlanningExecutor = null;
        this.poiScanExecutor = null;
        this.terrainCacheExecutor = null;

        CeaselessCaG.LOGGER.info("[DynamicManager] JIT 管理器已关闭。");
    }


    public void onTick(ServerTickEvent.Post event) {
        if (this.isShutdown.get()) {
            return;
        }


        this.tickCounter++;
        if (this.tickCounter >= JIT_TICK_INTERVAL) {
            this.tickCounter = 0;

            this.updateJitTasks(event.getServer());
        }
    }


    private void shutdownExecutorService(ExecutorService service, String name) {
        if (service == null || service.isShutdown()) {
            return;
        }

        CeaselessCaG.LOGGER.info("[DynamicManager] 正在关闭 {} 线程池...", name);


        service.shutdown();
        try {

            if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
                CeaselessCaG.LOGGER.warn("[DynamicManager] {} 线程池在 5 秒内未终止，强制关闭...", name);
                service.shutdownNow();


                if (!service.awaitTermination(2, TimeUnit.SECONDS)) {
                    CeaselessCaG.LOGGER.error("[DynamicManager] {} 线程池未能终止！", name);
                }
            } else {
                CeaselessCaG.LOGGER.info("[DynamicManager] {} 线程池已成功关闭。", name);
            }
        } catch (InterruptedException e) {

            CeaselessCaG.LOGGER.error("[DynamicManager] 关闭 {} 线程池时被中断。", name, e);
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    /**
     * JIT 任务分配器。检查所有玩家周围，找到需要缓存地形和扫描POI的区块，并将其添加到各自的 JIT 队列中。
     */
    private void updateJitTasks(MinecraftServer server) {
        TerrainDataManager.TerrainData terrainData = TerrainDataManager.getInstance().getDataSnapshot();

        if (terrainData == null || !terrainData.isReady()) {
            return;
        }

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        RoadNetworkData roadData = RoadNetworkData.get(overworld);

        List<ServerPlayer> players = overworld.players();
        if (players.isEmpty()) {
            return;
        }

        Set<Long> knownTerrainChunkIds = terrainData.getKnownChunkIds();
        Set<Long> knownPoiScanChunkIds = roadData.getKnownPoiScanChunks();

        int terrainTasksAdded = 0;
        int poiTasksAdded = 0;

        Set<ChunkPos> allChunksToUpdate = new HashSet<>();
        int maxRadius = Math.max(JIT_TERRAIN_CACHE_RADIUS_CHUNKS, JIT_POI_SCAN_RADIUS_CHUNKS);
        BlockPos priorityCenter = players.get(0).blockPosition();

        for (ServerPlayer player : players) {
            ChunkPos playerChunk = player.chunkPosition();
            for (int dx = -maxRadius; dx <= maxRadius; dx++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    if (dx * dx + dz * dz > maxRadius * maxRadius) {
                        continue;
                    }
                    allChunksToUpdate.add(new ChunkPos(playerChunk.x + dx, playerChunk.z + dz));
                }
            }
        }

        for (ChunkPos currentChunk : allChunksToUpdate) {
            long currentChunkId = currentChunk.toLong();

            if (currentChunk.getChessboardDistance(players.get(0).chunkPosition()) <= JIT_TERRAIN_CACHE_RADIUS_CHUNKS) {
                if (!knownTerrainChunkIds.contains(currentChunkId)) {
                    if (this.pendingTerrainChunks.add(currentChunkId)) {
                        double distanceSq = currentChunk.getMiddleBlockPosition(priorityCenter.getY()).distSqr(priorityCenter);
                        this.terrainCacheQueue.offer(new JitTask(currentChunk, distanceSq, server));
                        terrainTasksAdded++;
                    }
                }
            }

            if (POI_SCAN_RATE_PER_TICK > 0 &&
                    currentChunk.getChessboardDistance(players.get(0).chunkPosition()) <= JIT_POI_SCAN_RADIUS_CHUNKS) {

                if (!knownPoiScanChunkIds.contains(currentChunkId)) {
                    if (this.pendingPoiScanChunks.add(currentChunkId)) {
                        double distanceSq = currentChunk.getMiddleBlockPosition(priorityCenter.getY()).distSqr(priorityCenter);
                        this.poiScanQueue.offer(new JitTask(currentChunk, distanceSq, server));
                        poiTasksAdded++;
                    }
                }
            }
        }

        // if (terrainTasksAdded > 0 || poiTasksAdded > 0) {
        //     CeaselessCaG.LOGGER.debug("[DynamicManager] JIT 任务已更新。地形: +{} (总{}), POI: +{} (总{})",
        //             terrainTasksAdded, terrainCacheQueue.size(),
        //             poiTasksAdded, poiScanQueue.size());
        // }
    }


    /**
     * JIT 地形缓存工作线程循环
     * 由 start() 提交到 `terrainCacheExecutor` 线程池中。
     */
    private void terrainCacheWorkerLoop() {
        String threadName = Thread.currentThread().getName();
        CeaselessCaG.LOGGER.info("[DynamicManager-TerrainWorker] JIT 地形工作线程 {} 已启动。", threadName);

        while (!this.isShutdown.get()) {
            JitTask task = null;
            try {

                task = this.terrainCacheQueue.take();

                if (this.isShutdown.get()) {
                    break;
                }

                MinecraftServer server = task.server;
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                if (level == null) continue;

                ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
                if (!(chunkGenerator instanceof NoiseBasedChunkGenerator noiseGen)) {
                    continue;
                }
                RandomState randomState = level.getChunkSource().randomState();

                short[][] heightmap = TerrainHeightCalculator.calculateHeightmapForSingleChunk(
                        task.chunkPos, noiseGen, randomState
                );

                if (heightmap != null) {
                    TerrainDataManager.getInstance().updateSingleChunkData(task.chunkPos.toLong(), heightmap);
                }

            } catch (InterruptedException e) {
                CeaselessCaG.LOGGER.info("[DynamicManager-TerrainWorker] 工作线程 {} 被中断，正在退出...", threadName);
                break;
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[DynamicManager-TerrainWorker] 工作线程 {} 捕获到意外错误:", threadName, e);
            } finally {
                if (task != null) {
                    this.pendingTerrainChunks.remove(task.chunkPos.toLong());
                }
            }
        }
        CeaselessCaG.LOGGER.info("[DynamicManager-TerrainWorker] JIT 地形工作线程 {} 已停止。", threadName);
    }

    private static class JitTask implements Comparable<JitTask> {
        final ChunkPos chunkPos;
        final double distanceSq;
        final MinecraftServer server;

        JitTask(ChunkPos chunkPos, double distanceSq, MinecraftServer server) {
            this.chunkPos = chunkPos;
            this.distanceSq = distanceSq;
            this.server = server;
        }

        @Override
        public int compareTo(JitTask other) {
            return Double.compare(this.distanceSq, other.distanceSq);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            JitTask jitTask = (JitTask) o;
            return chunkPos.equals(jitTask.chunkPos);
        }

        @Override
        public int hashCode() {
            return chunkPos.hashCode();
        }
    }



    /**
     * JIT POI 扫描工作线程循环
     */
    private void poiScanWorkerLoop() {
        String threadName = Thread.currentThread().getName();
        CeaselessCaG.LOGGER.info("[DynamicManager-POIWorker] JIT POI 工作线程 {} 已启动。", threadName);

        while (!this.isShutdown.get()) {
            JitTask task = null;
            try {
                task = this.poiScanQueue.take();
                if (this.isShutdown.get()) break;

                ServerLevel level = task.server.getLevel(Level.OVERWORLD);
                if (level == null) continue;
                RoadNetworkData roadData = RoadNetworkData.get(level);

                ScanResult result = scanPoiInChunk(level, task.chunkPos);

                if (result.chunkWasReady()) {
                    roadData.markChunkPoiScanned(task.chunkPos);
                } else {
                    CeaselessCaG.LOGGER.trace("[DynamicManager-POIWorker] 区块 {} 尚未达到 STRUCTURE_STARTS。将重试。", task.chunkPos);
                    continue;
                }
                if (!result.pois().isEmpty()) {
                    Set<BlockPos> trulyNewPois = new HashSet<>();
                    for (BlockPos poiPos : result.pois()) {
                        if (roadData.tryDiscoverPoi(poiPos)) {
                            CeaselessCaG.LOGGER.info("[DynamicManager-POIWorker] JIT 扫描器发现了一个新POI: {} 于区块 {}", poiPos, task.chunkPos);
                            trulyNewPois.add(poiPos);
                        }
                    }

                    if (!trulyNewPois.isEmpty()) {
                        this.triggerIncrementalPlanning(level, trulyNewPois);
                    }
                }

            } catch (InterruptedException e) {
                CeaselessCaG.LOGGER.info("[DynamicManager-POIWorker] 工作线程 {} 被中断，正在退出...", threadName);
                break;
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[DynamicManager-POIWorker] 工作线程 {} 捕获到意外错误:", threadName, e);
            } finally {

                if (task != null) {
                    this.pendingPoiScanChunks.remove(task.chunkPos.toLong());
                }
            }
        }

        CeaselessCaG.LOGGER.info("[DynamicManager-POIWorker] JIT POI 工作线程 {} 已停止。", threadName);
    }

    /**
     * 在单个区块中扫描 POI。
     * @param level    主世界
     * @param chunkPos 要扫描的区块
     * @return `ScanResult`
     */
    private ScanResult scanPoiInChunk(ServerLevel level, ChunkPos chunkPos) {
        Set<BlockPos> foundPois = new HashSet<>();

        StructureManager structureManager = level.structureManager();
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);

        ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS, false);

        if (chunk == null) {

            return new ScanResult(foundPois, false);
        }

        SectionPos sectionPos = SectionPos.bottomOf(chunk);

        for (ResourceKey<Structure> poiKey : PoiRegistry.getRegisteredPois()) {

            Optional<Holder.Reference<Structure>> structureHolder = structureRegistry.getHolder(poiKey);
            if (structureHolder.isEmpty()) {
                continue;
            }

            StructureStart start = structureManager.getStartForStructure(sectionPos, structureHolder.get().value(), chunk);

            if (start != null && start.isValid()) {
                BlockPos centerPos = start.getBoundingBox().getCenter();
                foundPois.add(centerPos);
            }
        }

        return new ScanResult(foundPois, true);
    }


    /**
     * 触发增量规划。
     * @param level          主世界
     * @param newlyFoundPois 刚刚被发现的新 POI 集合
     */
    private void triggerIncrementalPlanning(ServerLevel level, Set<BlockPos> newlyFoundPois) {
        if (this.pathPlanningExecutor == null || this.pathPlanningExecutor.isShutdown()) {
            CeaselessCaG.LOGGER.error("[DynamicManager] 无法触发增量规划：路径规划线程池未运行！");
            return;
        }

        CeaselessCaG.LOGGER.debug("[DynamicManager] 触发增量规划，涉及 {} 个新 POI...", newlyFoundPois.size());

        CompletableFuture.runAsync(() -> {
            runIncrementalPlanningTask(level, newlyFoundPois);
        }, this.pathPlanningExecutor).exceptionally(ex -> {
            CeaselessCaG.LOGGER.error("[DynamicManager-PlanningWorker] 增量规划任务失败:", ex);
            return null;
        });
    }

    /**
     * 实际执行增量规划的任务。
     * @param level          主世界
     * @param newlyFoundPois 新 POI 集合
     */
    private void runIncrementalPlanningTask(ServerLevel level, Set<BlockPos> newlyFoundPois) {
        String threadName = Thread.currentThread().getName();
        CeaselessCaG.LOGGER.info("[DynamicManager-PlanningWorker] ({}) L正在为 {} 个新 POI 执行增量规划...", threadName, newlyFoundPois.size());

        RoadNetworkData roadData = RoadNetworkData.get(level);

        Set<RoadSegment> newSegments = RoadNetworkPlanner.planIncremental(newlyFoundPois, roadData);

        if (newSegments.isEmpty()) {
            CeaselessCaG.LOGGER.info("[DynamicManager-PlanningWorker] ({}) 增量规划未产生新的道路分段。", threadName);
            return;
        }

        CeaselessCaG.LOGGER.info("[DynamicManager-PlanningWorker] ({}) 增量规划产生了 {} 条新道路。正在提交进行路径计算...", threadName, newSegments.size());

        ArchitectManager.INSTANCE.calculateNewPathsAsync(level, newSegments);
    }

    private record ScanResult(Set<BlockPos> pois, boolean chunkWasReady) {}
}