package euphy.upo.ceaselesscag.world.architect;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.builder.planning.PreConstructionPlanner;
import euphy.upo.ceaselesscag.world.data.RoadSegment;
import euphy.upo.ceaselesscag.world.data.RoadNetworkData;
import euphy.upo.ceaselesscag.world.pathfinding.InterpolationSegment;
import euphy.upo.ceaselesscag.world.pathfinding.MicroPathfinder;
import euphy.upo.ceaselesscag.world.pathfinding.MjpsPathfinder;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;
import euphy.upo.ceaselesscag.world.terrain.TerrainHeightCalculator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import java.util.concurrent.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 架构管理器 (Architect Manager)。
 * 1. 管理线程池: 管理一个专用的`pathCalculationExecutor` 线程池
 * 2. 接收任务: 提供一个 JIT API `calculateNewPathsAsync(Set<RoadSegment>)`，供 `DynamicRoadManager` 提交新规划的道路。
 * 3. 执行计算: 在后台线程池中，为每个 `RoadSegment` 异步执行完整的计算流程 。
 */
public class ArchitectManager {

    public static final ArchitectManager INSTANCE = new ArchitectManager();

    /**
     * 线程安全的后台线程池。
     */
    private ExecutorService pathCalculationExecutor;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private ArchitectManager() {}

    /**
     * 创建并启动路径计算线程池。
     */
    public void start() {
        this.isShutdown.set(false);
        if (this.pathCalculationExecutor == null || this.pathCalculationExecutor.isShutdown()) {
            this.pathCalculationExecutor = Executors.newWorkStealingPool();
            CeaselessCaG.LOGGER.info("[ArchitectManager] Path Calculation 线程池启动 。");
        }
    }

    /**
     * 关闭路径计算线程池。
     */
    public void shutdown() {
        if (!this.isShutdown.compareAndSet(false, true)) {
            return;
        }

        shutdownExecutorService(this.pathCalculationExecutor, "JIT-Path-Calculation");
        this.pathCalculationExecutor = null;
    }



    /**
     * API - 异步计算新道路。
     * @param level       主世界
     * @param newSegments 一组新规划的道路分段
     */
    public void calculateNewPathsAsync(ServerLevel level, Set<RoadSegment> newSegments) {
        if (this.isShutdown.get() || this.pathCalculationExecutor == null) {
            return;
        }

        final RoadNetworkData roadData = RoadNetworkData.get(level);
        final TerrainDataManager.TerrainData terrainData = TerrainDataManager.getInstance().getDataSnapshot();

        if (terrainData == null || !TerrainDataManager.getInstance().isDataReady()) {
            CeaselessCaG.LOGGER.error("[ArchitectManager] 无法计算路径：地形数据未就绪");
            return;
        }

        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        if (!(chunkGenerator instanceof NoiseBasedChunkGenerator noiseGen)) {
            CeaselessCaG.LOGGER.error("[ArchitectManager] 无法计算路径：区块生成器不是 NoiseBasedChunkGenerator");
            return;
        }
        final RandomState randomState = level.getChunkSource().randomState();

        int submittedCount = 0;
        for (RoadSegment segment : newSegments) {

            if (roadData.isMicroPathBlueprint(segment)) {
                continue;
            }

            CeaselessCaG.LOGGER.debug("[ArchitectManager] 正在为 {} 提交路径计算", segment);

            CompletableFuture
                    .supplyAsync(() -> {
                        long startTime = System.nanoTime();
                        try {
                            BlockPos startPos = segment.pos1();
                            BlockPos endPos = segment.pos2();
                            final int INVALID_HEIGHT = terrainData.getInvalidHeight();
                            ChunkPos startChunk = new ChunkPos(startPos);
                            ChunkPos endChunk = new ChunkPos(endPos);
                            int minChunkX = Math.min(startChunk.x, endChunk.x);
                            int maxChunkX = Math.max(startChunk.x, endChunk.x);
                            int minChunkZ = Math.min(startChunk.z, endChunk.z);
                            int maxChunkZ = Math.max(startChunk.z, endChunk.z);

                            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {

                                    BlockPos chunkCenter = new ChunkPos(cx, cz).getMiddleBlockPosition(0);

                                    if (terrainData.getSurfaceHeight(chunkCenter.getX(), chunkCenter.getZ()) == INVALID_HEIGHT) {

                                        ChunkPos chunkToCalc = new ChunkPos(cx, cz);
                                        short[][] heightmap = TerrainHeightCalculator.calculateHeightmapForSingleChunk(
                                                chunkToCalc, noiseGen, randomState
                                        );

                                        if (heightmap != null) {
                                            TerrainDataManager.getInstance().updateSingleChunkData(chunkToCalc.toLong(), heightmap);
                                        }
                                    }
                                }
                            }

                            int startY = terrainData.getSurfaceHeight(startPos.getX(), startPos.getZ());
                            int endY = terrainData.getSurfaceHeight(endPos.getX(), endPos.getZ());

                            BlockPos preciseStartPos = new BlockPos(startPos.getX(), startY, startPos.getZ());
                            BlockPos preciseEndPos = new BlockPos(endPos.getX(), endY, endPos.getZ());

                            MjpsPathfinder mjpsPathfinder = new MjpsPathfinder(preciseStartPos, preciseEndPos, terrainData);
                            List<InterpolationSegment> macroPath = mjpsPathfinder.findPath();
                            if (macroPath == null || macroPath.isEmpty()) {
                                throw new RuntimeException("Mjps 失败: 未找到路径。");
                            }

                            List<BlockPos> denseMicroPath = MicroPathfinder.interpolatePath(macroPath, terrainData);
                            if (denseMicroPath == null || denseMicroPath.isEmpty()) {
                                throw new RuntimeException("MicroPathfinder 失败: 路径为空。");
                            }

                            return new CalculationResult(macroPath, denseMicroPath, startTime);

                        } catch (Exception e) {
                            CeaselessCaG.LOGGER.error("[ArchitectManager-Worker] 阶段 0/1/2 计算 {} 失败:", segment, e);
                            throw new CompletionException(e);
                        }

                    }, this.pathCalculationExecutor)

                    .thenAcceptAsync((result) -> {
                        try {
                            ConstructionPlan plan = PreConstructionPlanner.createPlan(
                                    level,
                                    terrainData,
                                    result.macroPath(),
                                    result.denseMicroPath()
                            );

                            if (plan == null || plan.finalBlueprint().isEmpty()) {
                                throw new RuntimeException("PreConstructionPlanner 失败: 蓝图为空。");
                            }

                            boolean blueprintAdded = roadData.addMicroPathBlueprint(segment, plan.finalBlueprint());
                            roadData.addConstructionSegments(segment, plan.segments());

                            if (blueprintAdded) {
                                long endTime = System.nanoTime();
                                CeaselessCaG.LOGGER.info("[ArchitectManager-MainThread]计算规划并存储: {} ({} 拐点, {} 施工分段)。总耗时 {}ms。",
                                        segment, plan.finalBlueprint().size(),
                                        plan.segments().size(),
                                        TimeUnit.NANOSECONDS.toMillis(endTime - result.startTime()));
                            } else {
                                CeaselessCaG.LOGGER.warn("[ArchitectManager-MainThread] 计算 {} 完成，但存储失败。", segment);
                            }
                        } catch (Exception e) {
                            CeaselessCaG.LOGGER.error("[ArchitectManager-MainThread] 规划/存储 {} 失败:", segment, e);
                        }

                    }, level.getServer())

                    .exceptionally(ex -> {
                        CeaselessCaG.LOGGER.error("[ArchitectManager-Worker] 路径计算管道 {} 期间发生错误 :", segment, ex);
                        return null;
                    });

            submittedCount++;
        }

        if (submittedCount > 0) {
            CeaselessCaG.LOGGER.info("[ArchitectManager] 提交 {} 个新的路径计算任务。", submittedCount);
        }
    }


    private void shutdownExecutorService(ExecutorService service, String name) {
        if (service == null || service.isShutdown()) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
                service.shutdownNow();
                if (!service.awaitTermination(2, TimeUnit.SECONDS)) {
                    CeaselessCaG.LOGGER.error("[ArchitectManager] {} 线程池未能终止", name);
                }
            } else {
                CeaselessCaG.LOGGER.info("[ArchitectManager] {} 线程池已成功关闭。", name);
            }
        } catch (InterruptedException e) {
            CeaselessCaG.LOGGER.error("[ArchitectManager] 关闭 {} 线程池时被中断。", name, e);
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }



    public Executor getPathCalculationExecutor() {
        if (this.isShutdown.get() || this.pathCalculationExecutor == null) {
            throw new IllegalStateException("ArchitectManager 未在运行");
        }
        return this.pathCalculationExecutor;
    }


    private record CalculationResult(
            List<InterpolationSegment> macroPath,
            List<BlockPos> denseMicroPath,
            long startTime
    ) {}



    /**
     * 等待就绪
     * 尝试从地形缓存中获取地表高度。如果缓存未命中，将同步调用地形计算器来强制计算，并更新缓存。
     * 必须在后台线程中调用
     * @param level       主世界
     * @param terrainData 地形缓存快照
     * @param pos         要查询的坐标
     * @return 物理地表 Y 坐标，如果强制计算后仍然失败，则返回 INVALID_HEIGHT
     */
    @Deprecated
    private int getOrCalculateHeight(
            ServerLevel level,
            TerrainDataManager.TerrainData terrainData,
            BlockPos pos
    ) {
        final int INVALID_HEIGHT = terrainData.getInvalidHeight();
        int y = terrainData.getSurfaceHeight(pos.getX(), pos.getZ());

        if (y != INVALID_HEIGHT) {
            return y;
        }
        try {
            ChunkPos chunkPos = new ChunkPos(pos);
            ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();

            if (!(chunkGenerator instanceof NoiseBasedChunkGenerator noiseGen)) {
                return INVALID_HEIGHT;
            }
            RandomState randomState = level.getChunkSource().randomState();

            short[][] heightmap = TerrainHeightCalculator.calculateHeightmapForSingleChunk(
                    chunkPos, noiseGen, randomState
            );

            if (heightmap == null) {
                return INVALID_HEIGHT;
            }

            TerrainDataManager.getInstance().updateSingleChunkData(chunkPos.toLong(), heightmap);

            int relX = Math.floorMod(pos.getX(), 16);
            int relZ = Math.floorMod(pos.getZ(), 16);

            return heightmap[relX][relZ];

        } catch (Exception e) {
            return INVALID_HEIGHT;
        }
    }
}