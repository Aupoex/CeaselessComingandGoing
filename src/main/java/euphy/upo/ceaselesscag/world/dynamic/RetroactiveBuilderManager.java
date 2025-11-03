package euphy.upo.ceaselesscag.world.dynamic;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.RoadBuilderManager;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.data.RoadNetworkData;
import euphy.upo.ceaselesscag.world.data.RoadSegment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 施工管理器
 * 定期扫描玩家周围的区块，并查询 `RoadNetworkData` 寻找未建造的蓝图。
 */
public class RetroactiveBuilderManager {

    public static final RetroactiveBuilderManager INSTANCE = new RetroactiveBuilderManager();
    /**
     * 启用开关
     */
    private static final boolean BUILDER_ENABLED = true;

    private static final int CHECK_RADIUS_CHUNKS = 40;

    /**
     * 施工速度：每步建造多少个方块
     */
    private static final int BLOCKS_TO_PLACE_PER_STEP = 30;

    /**
     * 施工频率：每多久执行一次施工
     */
    private static final int BUILD_STEP_INTERVAL_TICKS = 10;

    /**
     * 扫描频率：每多久扫描一次新任务
     */
    private static final int TASK_SEARCH_INTERVAL_TICKS = 60;

    private final Queue<BuildTask> buildQueue = new ConcurrentLinkedQueue<>();

    private final Set<RoadSegment> segmentsInQueue = Collections.newSetFromMap(new WeakHashMap<>());

    private int buildStepTickCounter = 0;
    private int taskSearchTickCounter = 0;


    private boolean isServerRunning = false;


    private RetroactiveBuilderManager() {
        //NeoForge.EVENT_BUS.register(this);
    }


    public void start() {
        this.isServerRunning = true;
        this.buildQueue.clear();
        this.segmentsInQueue.clear();
        this.buildStepTickCounter = 0;
        this.taskSearchTickCounter = 0;
        CeaselessCaG.LOGGER.info("[ConstructionManager] 施工管理器已启动。");
    }


    public void shutdown() {
        this.isServerRunning = false;
        this.buildQueue.clear();
        this.segmentsInQueue.clear();
        CeaselessCaG.LOGGER.info("[ConstructionManager] 施工管理器已关闭。");
        //NeoForge.EVENT_BUS.unregister(this);
    }


    public void onTick(ServerTickEvent.Post event) {
        if (!isServerRunning || !BUILDER_ENABLED) {
            return;
        }

        buildStepTickCounter++;
        if (buildStepTickCounter >= BUILD_STEP_INTERVAL_TICKS) {
            buildStepTickCounter = 0;
            executeBuildStep(event.getServer());
        }

        taskSearchTickCounter++;
        if (taskSearchTickCounter >= TASK_SEARCH_INTERVAL_TICKS) {
            taskSearchTickCounter = 0;
            searchForNewTasks(event.getServer());
        }
    }

    /**
     * 执行一步施工
     */
    private void executeBuildStep(MinecraftServer server) {
        BuildTask currentTask = buildQueue.peek();
        if (currentTask == null) {
            return;
        }

        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }

        // CeaselessCaG.LOGGER.debug("[ConstructionManager] 正在执行施工步骤: {}", currentTask.segment);

        boolean isFinished = currentTask.executeStep(level, BLOCKS_TO_PLACE_PER_STEP);

        if (isFinished) {
            buildQueue.poll();
            segmentsInQueue.remove(currentTask.segment);
            cleanUpDroppedItems(level, currentTask, RoadNetworkData.get(level));
            RoadNetworkData.get(level).markSegmentAsBuilt(currentTask.segment);

            /*
            CeaselessCaG.LOGGER.info("[ConstructionManager] 【完成】施工: {}。剩余任务: {}",
                    currentTask.segment, buildQueue.size());
             */
        }
    }

    private void cleanUpDroppedItems(ServerLevel level, BuildTask finishedTask, RoadNetworkData roadData) {

        //TODO 清理道路方块范围内掉落物
        final int halfWidth = 1;

        List<ConstructionPlan.ConstructionSegment> segments = roadData.getConstructionSegments(finishedTask.segment);
        if (segments.isEmpty()) {
            return;
        }

        HashSet<BlockPos> clearedPositions = new HashSet<>();

        for (ConstructionPlan.ConstructionSegment segment : segments) {

            if (segment.type() == ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE) {
                continue;
            }

            Direction facing = getApproximateDirection(segment.start(), segment.end());
            Direction crossDirection = facing.getCounterClockWise();

            for (BlockPos centerPos : RoadBuilderManager.bresenham3D(segment.start(), segment.end())) {

                for (int offset = -halfWidth; offset <= halfWidth; offset++) {

                    BlockPos roadSurfacePos = centerPos.relative(crossDirection, offset);

                    BlockPos spaceToClear = roadSurfacePos.above();

                    if (clearedPositions.add(spaceToClear)) {
                        AABB scanBox = new AABB(spaceToClear);
                        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, scanBox);

                        for (ItemEntity item : items) {
                            item.discard();
                        }
                    }
                }
            }
        }
    }


    private Direction getApproximateDirection(BlockPos segmentStart, BlockPos segmentEnd) {
        if (segmentStart == null || segmentEnd == null) {
            return Direction.NORTH;
        }

        int dx = segmentEnd.getX() - segmentStart.getX();
        int dz = segmentEnd.getZ() - segmentStart.getZ();

        if (Math.abs(dx) > Math.abs(dz)) {
            return (dx > 0) ? Direction.EAST : Direction.WEST;
        } else {
            return (dz > 0) ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /**
     * 扫描玩家周围，查找新任务
     */
    private void searchForNewTasks(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        RoadNetworkData roadData = RoadNetworkData.get(level);

        Set<ChunkPos> chunksToScan = new HashSet<>();
        for (ServerPlayer player : players) {
            ChunkPos playerChunk = player.chunkPosition();
            for (int dx = -CHECK_RADIUS_CHUNKS; dx <= CHECK_RADIUS_CHUNKS; dx++) {
                for (int dz = -CHECK_RADIUS_CHUNKS; dz <= CHECK_RADIUS_CHUNKS; dz++) {
                    chunksToScan.add(new ChunkPos(playerChunk.x + dx, playerChunk.z + dz));
                }
            }
        }

        // CeaselessCaG.LOGGER.debug("[ConstructionManager] 正在 {} 个区块中扫描新任务...", chunksToScan.size());
        int tasksAdded = 0;

        for (ChunkPos chunkPos : chunksToScan) {

            Set<RoadSegment> unbuiltSegments = roadData.findUnbuiltSegmentsInChunk(chunkPos);

            for (RoadSegment segment : unbuiltSegments) {

                synchronized (segmentsInQueue) {
                    if (segmentsInQueue.add(segment)) {

                        BuildTask newTask = new BuildTask(segment, roadData, level);

                        buildQueue.offer(newTask);
                        tasksAdded++;
                    }
                }
            }
        }

        if (tasksAdded > 0) {
            CeaselessCaG.LOGGER.info("[ConstructionManager] 发现了 {} 个新任务。队列总数: {}",
                    tasksAdded, buildQueue.size());
        }
    }
}