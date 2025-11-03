package euphy.upo.ceaselesscag.world.pathfinding;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 宏观 JPS 寻路器 (Macro JPS Pathfinder) 。
 * 1: 粗略区块路径: 在区块网格上运行 A*，找到成本最低的区块路径 `[C1, ..., Cn]`。
 * 2: 精确地表路径: 使用垂直线+高度匹配，将 `[C1, ..., Cn]` 转换为精确的、贴地的拐点 `[P1, ..., Pn]`。
 * 3: 贴地路径拉直: 使用约束+距离比较，拉直 `[P1, ..., Pn]` 中的平地锯齿。
 * 4: Y轴抹平: 扫描拉直后的路径，通过Y值抹平来隐式地创建桥梁和隧道。
 * 最终输出是一个`List<BlockPos>` 拐点列表。
 */
public class MjpsPathfinder {

    private static final int MACRO_CELL_SIZE = 16;
    private static final double INTERNAL_SLOPE_MULTIPLIER = 1.0;
    private static final double TUNNEL_COST_PER_BLOCK = 2.0;
    private static final double TRANSITION_CLIFF_MULTIPLIER = 1.0;
    private static final int MAX_STRAIGHTEN_ENDPOINT_Y_DIFF = 1;
    private final BlockPos startPos;
    private final BlockPos goalPos;
    private final ChunkPos startChunk;
    private final ChunkPos goalChunk;
    private final TerrainDataManager.TerrainData terrainData;

    private final Map<ChunkPos, TerrainProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<Long, Double> transitionCache = new ConcurrentHashMap<>();
    private final PriorityQueue<Node> openSet;
    private final Map<ChunkPos, Node> allNodes = new ConcurrentHashMap<>();


    /**
     * 拉直算法所允许的最大单步台阶高度。
     */
    private static final int MAX_STEP_HEIGHT = 1;

    /**
     * 垂直落差超过多少格时，被认为是悬崖起点。
     */
    private static final int Y_SMOOTH_DROP_THRESHOLD = 4;

    /**
     * 向前探查多远格来寻找对岸。
     */
    private static final int Y_SMOOTH_LOOKAHEAD_DISTANCE = 1024;

    /**
     * 两岸的高度差在多少格以内，被认为是高度相似。
     */
    private static final int Y_SMOOTH_SIMILARITY_THRESHOLD = 8;

    public MjpsPathfinder(BlockPos startPos, BlockPos goalPos, TerrainDataManager.TerrainData terrainData) {
        this.terrainData = terrainData;

        if (this.terrainData == null || !this.terrainData.isReady()) {
            throw new IllegalStateException("宏观寻路器 (MjpsPathfinder) 启动失败：地形数据未准备好！");
        }

        this.startPos = startPos;
        this.goalPos = goalPos;
        this.startChunk = new ChunkPos(startPos);
        this.goalChunk = new ChunkPos(goalPos);

        long profileStartTime = System.nanoTime();
        Set<Long> knownChunkIds = this.terrainData.getKnownChunkIds();
        knownChunkIds.parallelStream().forEach(chunkId -> {
            ChunkPos pos = new ChunkPos(chunkId);
            this.profileCache.put(pos, new TerrainProfile(pos, this.terrainData));
        });
        long profileEndTime = System.nanoTime();
        CeaselessCaG.LOGGER.debug("[MjpsPathfinder] 内部成本 (TerrainProfile) 预计算耗时 {}ms ({} 个区块)。",
                TimeUnit.NANOSECONDS.toMillis(profileEndTime - profileStartTime), knownChunkIds.size());

        if (!this.profileCache.containsKey(startChunk) || !this.profileCache.containsKey(goalChunk)) {
            CeaselessCaG.LOGGER.error("[MjpsPathfinder] 寻路失败：起点 {} 或终点 {} 不在 JIT 缓存区域内！", startChunk, goalChunk);
            throw new IllegalArgumentException("起点或终点区块不在已缓存的区域内。");
        }

        this.openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Node startNode = new Node(this.startChunk, 0, heuristic(this.startChunk, this.goalChunk), null);
        this.openSet.add(startNode);
        this.allNodes.put(this.startChunk, startNode);
    }


    /**
     * API - 执行四阶段宏观寻路。
     */
    public List<InterpolationSegment> findPath() {

        // 粗略区块路径
        long aStarStartTime = System.nanoTime();
        Node goalNode = findCoarseChunkPath();
        long aStarEndTime = System.nanoTime();
        CeaselessCaG.LOGGER.debug("[MjpsPathfinder-S1] A* 粗网格搜索耗时 {}ms。",
                TimeUnit.NANOSECONDS.toMillis(aStarEndTime - aStarStartTime));

        if (goalNode == null) {
            CeaselessCaG.LOGGER.warn("[MjpsPathfinder-S1] A* 粗网格寻路失败：无法在 {} 和 {} 之间找到路径。", startChunk, goalChunk);
            return Collections.emptyList();
        }

        List<Node> chunkPathNodes = reconstructChunkPath(goalNode);
        if (chunkPathNodes.size() < 2) {
            return List.of(new InterpolationSegment(this.startPos, this.goalPos, InterpolationType.STRAIGHT_LINE));
        }

        // 精确地表路径
        long preciseStartTime = System.nanoTime();
        List<BlockPos> preciseGroundPath = generatePreciseGroundPath(chunkPathNodes);
        long preciseEndTime = System.nanoTime();
        CeaselessCaG.LOGGER.debug("[MjpsPathfinder-S2] 精确地表路径 (垂直扫描) 耗时 {}ms ({} 个拐点)。",
                TimeUnit.NANOSECONDS.toMillis(preciseEndTime - preciseStartTime), preciseGroundPath.size());

        if (preciseGroundPath.size() < 2) {
            return List.of(new InterpolationSegment(this.startPos, this.goalPos, InterpolationType.STRAIGHT_LINE));
        }

        //  贴地路径拉直
        long straightenStartTime = System.nanoTime();
        List<BlockPos> straightenedPath = straightenGroundPath(preciseGroundPath);
        long straightenEndTime = System.nanoTime();
        CeaselessCaG.LOGGER.debug("[MjpsPathfinder-S3] 贴地路径拉直 (路程优化) 耗时 {}ms (从 {} -> {} 个拐点)。",
                TimeUnit.NANOSECONDS.toMillis(straightenEndTime - straightenStartTime), preciseGroundPath.size(), straightenedPath.size());

        // Y轴抹平
        long ySmoothStartTime = System.nanoTime();
        List<InterpolationSegment> finalInterpolationSegments = optimizeAndConvertToSegments(straightenedPath);
        long ySmoothEndTime = System.nanoTime();
        CeaselessCaG.LOGGER.debug("[MjpsPathfinder-S4] Y轴抹平耗时 {}ms ({} 个拐点 -> {} 个分段)。",
                TimeUnit.NANOSECONDS.toMillis(ySmoothEndTime - ySmoothStartTime), straightenedPath.size(), finalInterpolationSegments.size());

        return finalInterpolationSegments;
    }


    /**
     * 在区块网格上运行 A* 算法
     */
    private Node findCoarseChunkPath() {
        Set<ChunkPos> closedSet = new HashSet<>();
        Node goalNode = null;

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            if (current.pos.equals(goalChunk)) {
                goalNode = current;
                break;
            }
            if (!closedSet.add(current.pos)) {
                continue;
            }
            if (!this.profileCache.containsKey(current.pos)) {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    ChunkPos neighborPos = new ChunkPos(current.pos.x + dx, current.pos.z + dz);

                    if (closedSet.contains(neighborPos) || !this.profileCache.containsKey(neighborPos)) {
                        continue;
                    }

                    double gCost = current.gCost + getMoveCost(current.pos, neighborPos);

                    Node neighborNode = allNodes.get(neighborPos);
                    boolean isNewNode = (neighborNode == null);

                    if (isNewNode || gCost < neighborNode.gCost) {
                        if (isNewNode) {
                            neighborNode = new Node(neighborPos);
                            allNodes.put(neighborPos, neighborNode);
                        }
                        neighborNode.parent = current;
                        neighborNode.gCost = gCost;
                        neighborNode.hCost = heuristic(neighborPos, goalChunk);
                        neighborNode.fCost = neighborNode.gCost + neighborNode.hCost;

                        if (openSet.contains(neighborNode)) {
                            openSet.remove(neighborNode);
                        }
                        openSet.add(neighborNode);
                    }
                }
            }
        }
        return goalNode;
    }

    private List<Node> reconstructChunkPath(Node goalNode) {
        List<Node> path = new ArrayList<>();
        Node current = goalNode;
        while (current != null) {
            path.add(current);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private double getMoveCost(ChunkPos from, ChunkPos to) {
        double moveCost = (from.x == to.x || from.z == to.z) ? MACRO_CELL_SIZE : MACRO_CELL_SIZE * Math.sqrt(2.0);
        TerrainProfile toProfile = profileCache.get(to);
        double internalCost = (toProfile != null) ? toProfile.effectiveInternalCost : Double.POSITIVE_INFINITY;
        double transitionCost = calculateTransitionCost(from, to);
        return moveCost + internalCost + transitionCost;
    }

    private double calculateTransitionCost(ChunkPos from, ChunkPos to) {
        long pairId = ChunkPos.asLong(from.x, from.z) ^ (ChunkPos.asLong(to.x, to.z) << 32);
        return transitionCache.computeIfAbsent(pairId, id -> {
            TerrainProfile fromProfile = profileCache.get(from);
            TerrainProfile toProfile = profileCache.get(to);
            if (fromProfile == null || toProfile == null || !fromProfile.isCached || !toProfile.isCached) {
                return Double.POSITIVE_INFINITY;
            }
            double heightDiffPenalty = Math.max(0, Math.abs(fromProfile.avgHeight - toProfile.avgHeight) - 5.0) * 10.0;
            double cliffSum = 0;
            if (from.x != to.x && from.z != to.z) {
                cliffSum += checkBoundary(from, new ChunkPos(to.x, from.z));
                cliffSum += checkBoundary(new ChunkPos(to.x, from.z), to);
            } else {
                cliffSum += checkBoundary(from, to);
            }
            return (cliffSum * TRANSITION_CLIFF_MULTIPLIER) + heightDiffPenalty;
        });
    }

    private double checkBoundary(ChunkPos c1, ChunkPos c2) {
        double cliffSum = 0;
        final int INVALID_HEIGHT = terrainData.getInvalidHeight();
        if (c1.x != c2.x) {
            int boundaryX = (c2.x > c1.x) ? c2.getMinBlockX() : c1.getMinBlockX();
            int zStart = c1.getMinBlockZ();
            for (int z = 0; z < MACRO_CELL_SIZE; ++z) {
                int h1 = terrainData.getSurfaceHeight(boundaryX - 1, zStart + z);
                int h2 = terrainData.getSurfaceHeight(boundaryX, zStart + z);
                if (h1 != INVALID_HEIGHT && h2 != INVALID_HEIGHT) {
                    cliffSum += Math.abs(h1 - h2);
                } else {
                    cliffSum += 50;
                }
            }
        } else if (c1.z != c2.z) {
            int boundaryZ = (c2.z > c1.z) ? c2.getMinBlockZ() : c1.getMinBlockZ();
            int xStart = c1.getMinBlockX();
            for (int x = 0; x < MACRO_CELL_SIZE; ++x) {
                int h1 = terrainData.getSurfaceHeight(xStart + x, boundaryZ - 1);
                int h2 = terrainData.getSurfaceHeight(xStart + x, boundaryZ);
                if (h1 != INVALID_HEIGHT && h2 != INVALID_HEIGHT) {
                    cliffSum += Math.abs(h1 - h2);
                } else {
                    cliffSum += 50;
                }
            }
        }
        return cliffSum;
    }

    private double heuristic(ChunkPos a, ChunkPos b) {
        return (Math.abs(a.x - b.x) + Math.abs(a.z - b.z)) * MACRO_CELL_SIZE;
    }


    /**
     * 将粗略的区块路径 `[C1..Cn]` 转换为精确的贴地的拐点列表 `[P1..Pn]`。
     */
    private List<BlockPos> generatePreciseGroundPath(List<Node> chunkPathNodes) {
        List<BlockPos> precisePath = new ArrayList<>();

        BlockPos currentAnchor = this.startPos;
        precisePath.add(currentAnchor);

        for (int i = 1; i < chunkPathNodes.size() - 1; i++) {
            ChunkPos targetChunk = chunkPathNodes.get(i).pos;

            BlockPos nextPoint = findBestConnectingPoint(currentAnchor, targetChunk);

            if (nextPoint == null) {
                CeaselessCaG.LOGGER.warn("[MjpsPathfinder-S2] 无法在区块 {} 中找到最佳连接点。", targetChunk);
                continue;
            }

            precisePath.add(nextPoint);
            currentAnchor = nextPoint;
        }
        precisePath.add(this.goalPos);

        return precisePath;
    }

    /**
     * 拉直贴地路径中的平地锯齿。
     */
    private List<BlockPos> straightenGroundPath(List<BlockPos> preciseGroundPath) {
        if (preciseGroundPath.size() < 3) {
            return preciseGroundPath;
        }

        List<BlockPos> straightenedPath = new ArrayList<>();

        BlockPos anchorPoint = preciseGroundPath.get(0);
        straightenedPath.add(anchorPoint);

        int anchorIndex = 0;
        int currentIndex = 1;

        while (anchorIndex < preciseGroundPath.size() - 1) {
            int lookaheadIndex = currentIndex + 1;

            if (lookaheadIndex >= preciseGroundPath.size()) {
                if (anchorIndex < preciseGroundPath.size() - 1) {
                    straightenedPath.add(preciseGroundPath.get(preciseGroundPath.size() - 1));
                }
                break;
            }

            BlockPos lookaheadPoint = preciseGroundPath.get(lookaheadIndex);

            double curveDistance = calculatePathDistance(preciseGroundPath, anchorIndex, lookaheadIndex);

            double straightDistance = anchorPoint.distSqr(lookaheadPoint);

            if (straightDistance < curveDistance && isStraightPathPassable(anchorPoint, lookaheadPoint)) {

                currentIndex = lookaheadIndex;

                if (currentIndex == preciseGroundPath.size() - 1) {
                    straightenedPath.add(lookaheadPoint);
                    break;
                }

            } else {

                BlockPos necessaryTurnPoint = preciseGroundPath.get(currentIndex);
                straightenedPath.add(necessaryTurnPoint);

                anchorPoint = necessaryTurnPoint;
                anchorIndex = currentIndex;

                currentIndex = anchorIndex + 1;
            }
        }

        return straightenedPath;
    }

    /**
     * 抹平 Y
     */
    private List<InterpolationSegment> optimizeAndConvertToSegments(List<BlockPos> straightenedPath) {
        if (straightenedPath.size() < 2) {
            return Collections.emptyList();
        }

        List<InterpolationSegment> finalSegments = new ArrayList<>();

        int anchorIndex = 0;

        int i = 0;

        while (anchorIndex < straightenedPath.size() - 1) {
            i = anchorIndex;

            BlockPos pCurrent = straightenedPath.get(i);
            BlockPos pNext;

            if (i + 1 >= straightenedPath.size()) {
                break;
            }
            pNext = straightenedPath.get(i + 1);

            boolean isCliffDrop = (pCurrent.getY() - pNext.getY() >= Y_SMOOTH_DROP_THRESHOLD);
            if (isCliffDrop) {
                int oppositeIndex = findOppositeSide_Valley(straightenedPath, i);
                if (oppositeIndex != -1) {
                    BlockPos bridgeStartPoint = pCurrent;
                    BlockPos bridgeEndPoint = straightenedPath.get(oppositeIndex);

                    finalSegments.add(new InterpolationSegment(bridgeStartPoint, bridgeEndPoint, InterpolationType.STRAIGHT_LINE));
                    anchorIndex = oppositeIndex;
                    continue;
                }
            }


            boolean isCliffClimb = (pNext.getY() - pCurrent.getY() >= Y_SMOOTH_DROP_THRESHOLD);
            if (isCliffClimb) {
                int oppositeIndex = findOppositeSide_Mountain(straightenedPath, i);
                if (oppositeIndex != -1) {
                    BlockPos tunnelStartPoint = pCurrent;
                    BlockPos tunnelEndPoint = straightenedPath.get(oppositeIndex);

                    finalSegments.add(new InterpolationSegment(tunnelStartPoint, tunnelEndPoint, InterpolationType.STRAIGHT_LINE));
                    anchorIndex = oppositeIndex;
                    continue;
                }
            }


            finalSegments.add(new InterpolationSegment(
                    pCurrent,
                    pNext,
                    InterpolationType.GROUND_SMOOTH
            ));
            anchorIndex++;

        }

        return finalSegments;
    }

    /**
     * 向前探查，寻找深谷的对岸。
     * @param path       拉直后的路径
     * @param startIndex “悬崖起点”的索引 (i)
     * @return “对岸”的索引 (j)，如果没找到则返回 -1
     */
    private int findOppositeSide_Valley(List<BlockPos> path, int startIndex) {
        BlockPos cliffStart = path.get(startIndex);

        for (int j = startIndex + 2; j < path.size() && j < startIndex + Y_SMOOTH_LOOKAHEAD_DISTANCE; j++) {
            BlockPos pCandidate = path.get(j);

            if (Math.abs(pCandidate.getY() - cliffStart.getY()) <= Y_SMOOTH_SIMILARITY_THRESHOLD) {

                boolean isValley = true;
                for (int k = startIndex + 1; k < j; k++) {
                    if (path.get(k).getY() >= cliffStart.getY() - Y_SMOOTH_SIMILARITY_THRESHOLD) {
                        isValley = false;
                        break;
                    }
                }
                if (isValley) {
                    return j;
                }
            }
        }
        return -1;
    }

    /**
     * 向前探查，寻找山体的对岸。
     * @param path       拉直后的路径
     * @param startIndex “山脚起点”的索引 (i)
     * @return “山对面”的索引 (j)，如果没找到则返回 -1
     */
    private int findOppositeSide_Mountain(List<BlockPos> path, int startIndex) {
        BlockPos tunnelStart = path.get(startIndex);

        for (int j = startIndex + 2; j < path.size() && j < startIndex + Y_SMOOTH_LOOKAHEAD_DISTANCE; j++) {
            BlockPos pCandidate = path.get(j);

            if (Math.abs(pCandidate.getY() - tunnelStart.getY()) <= Y_SMOOTH_SIMILARITY_THRESHOLD) {

                boolean isMountain = true;
                for (int k = startIndex + 1; k < j; k++) {
                    if (path.get(k).getY() <= tunnelStart.getY() + Y_SMOOTH_SIMILARITY_THRESHOLD) {
                        isMountain = false;
                        break;
                    }
                }
                if (isMountain) {
                    return j;
                }
            }
        }
        return -1;
    }

    /**
     * A* 寻路节点
     */
    private static class Node {
        final ChunkPos pos;
        double gCost = Double.MAX_VALUE, hCost = 0, fCost = Double.MAX_VALUE;
        Node parent = null;
        public Node(ChunkPos pos) { this.pos = pos; }
        public Node(ChunkPos pos, double g, double h, Node parent) {
            this.pos = pos; this.gCost = g; this.hCost = h; this.fCost = g + h; this.parent = parent;
        }
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; return pos.equals(((Node) o).pos); }
        @Override public int hashCode() { return pos.hashCode(); }
    }


    /**
     * 存储一个区块的内部通行成本
     */
    private static class TerrainProfile {
        final double variance;
        final int avgHeight;
        final double internalWalkCost;
        final double effectiveInternalCost;
        final boolean isCached;


        public TerrainProfile(ChunkPos pos, TerrainDataManager.TerrainData data) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            long heightSum = 0;
            int sampleCount = 0;
            final int INVALID_HEIGHT = data.getInvalidHeight();

            int startX = pos.getMinBlockX();
            int startZ = pos.getMinBlockZ();
            int step = 4;
            int offset = step / 2;

            final int TOTAL_SAMPLES = (MACRO_CELL_SIZE / step) * (MACRO_CELL_SIZE / step);

            for (int x = offset; x < MACRO_CELL_SIZE; x += step) {
                for (int z = offset; z < MACRO_CELL_SIZE; z += step) {
                    int y = data.getSurfaceHeight(startX + x, startZ + z);
                    if (y > INVALID_HEIGHT) {
                        if (y < min) min = y;
                        if (y > max) max = y;
                        heightSum += y;
                        sampleCount++;
                    }
                }
            }

            if (sampleCount == TOTAL_SAMPLES) {
                this.isCached = true;
                this.variance = max - min;
                this.avgHeight = (int) (heightSum / sampleCount);
                this.internalWalkCost = this.variance * INTERNAL_SLOPE_MULTIPLIER;
            } else {
                this.isCached = false;
                this.variance = Double.POSITIVE_INFINITY;
                this.avgHeight = INVALID_HEIGHT;
                this.internalWalkCost = Double.POSITIVE_INFINITY;
            }

            double tunnelCost = (MACRO_CELL_SIZE * 1.414) * TUNNEL_COST_PER_BLOCK;
            this.effectiveInternalCost = Math.min(this.internalWalkCost, tunnelCost);
        }
    }


    /**
     * 垂直线 + 高度匹配
     * 在 `targetChunk` 中找到一个精确的、贴地的点，
     * @param anchorPoint 上一个精确的拐点
     * @param targetChunk 正在寻找下一个拐点的目标区块
     * @return 找到的最佳连接点，如果区块无效则返回 null
     */
    private BlockPos findBestConnectingPoint(BlockPos anchorPoint, ChunkPos targetChunk) {
        BlockPos targetCenter = targetChunk.getMiddleBlockPosition(anchorPoint.getY());

        int dx = targetCenter.getX() - anchorPoint.getX();
        int dz = targetCenter.getZ() - anchorPoint.getZ();

        int scanStartX, scanStartZ, scanStepX, scanStepZ;

        if (Math.abs(dx) > Math.abs(dz)) {

            scanStartX = targetCenter.getX();
            scanStartZ = targetChunk.getMinBlockZ();
            scanStepX = 0;
            scanStepZ = 1;
        } else {
            scanStartX = targetChunk.getMinBlockX();
            scanStartZ = targetCenter.getZ();
            scanStepX = 1;
            scanStepZ = 0;
        }

        BlockPos bestPoint = null;
        int minHeightDiff = Integer.MAX_VALUE;
        final int anchorY = anchorPoint.getY();
        final int INVALID_HEIGHT = terrainData.getInvalidHeight();

        for (int i = 0; i < 16; i++) {
            int currentX = scanStartX + i * scanStepX;
            int currentZ = scanStartZ + i * scanStepZ;

            int currentY = terrainData.getSurfaceHeight(currentX, currentZ);

            if (currentY == INVALID_HEIGHT) {
                continue;
            }

            int heightDiff = Math.abs(currentY - anchorY);
            if (heightDiff < minHeightDiff) {
                minHeightDiff = heightDiff;
                bestPoint = new BlockPos(currentX, currentY, currentZ);
            }
        }


        if (bestPoint == null) {

            CeaselessCaG.LOGGER.trace("[MjpsPathfinder-S2] 发现线在 {} 扫描失败 (未缓存)。回退到中心点。", targetChunk);
            int fallbackY = terrainData.getSurfaceHeight(targetCenter.getX(), targetCenter.getZ());
            if (fallbackY != INVALID_HEIGHT) {
                return new BlockPos(targetCenter.getX(), fallbackY, targetCenter.getZ());
            }
        }

        return bestPoint;
    }


    /**
     * 3D Bresenham 直线算法迭代器。
     */
    private static Iterable<BlockPos> bresenham3D(BlockPos p1, BlockPos p2) {
        return () -> new Iterator<>() {
            private int x, y, z;
            private final int dx, dy, dz;
            private final int stepX, stepY, stepZ;
            private final int err1, err2;
            private int e1, e2;
            private int i;
            private final int n;
            {
                x = p1.getX(); y = p1.getY(); z = p1.getZ();
                dx = Math.abs(p2.getX() - x); dy = Math.abs(p2.getY() - y); dz = Math.abs(p2.getZ() - z);
                stepX = Integer.compare(p2.getX(), x);
                stepY = Integer.compare(p2.getY(), y);
                stepZ = Integer.compare(p2.getZ(), z);
                if (dx >= dy && dx >= dz) { n = dx + 1; err1 = 2 * dy - dx; err2 = 2 * dz - dx; }
                else if (dy >= dx && dy >= dz) { n = dy + 1; err1 = 2 * dx - dy; err2 = 2 * dz - dy; }
                else { n = dz + 1; err1 = 2 * dx - dz; err2 = 2 * dy - dz; }
                e1 = err1; e2 = err2; i = 0;
            }
            @Override public boolean hasNext() { return i < n; }
            @Override public BlockPos next() {
                if (!hasNext()) throw new NoSuchElementException();
                BlockPos currentPos = new BlockPos(x, y, z); i++;
                if (dx >= dy && dx >= dz) {
                    if (e1 > 0) { y += stepY; e1 -= 2 * dx; }
                    if (e2 > 0) { z += stepZ; e2 -= 2 * dx; }
                    e1 += 2 * dy; e2 += 2 * dz; x += stepX;
                } else if (dy >= dx && dy >= dz) {
                    if (e1 > 0) { x += stepX; e1 -= 2 * dy; }
                    if (e2 > 0) { z += stepZ; e2 -= 2 * dy; }
                    e1 += 2 * dx; e2 += 2 * dz; y += stepY;
                } else {
                    if (e1 > 0) { x += stepX; e1 -= 2 * dz; }
                    if (e2 > 0) { y += stepY; e2 -= 2 * dz; }
                    e1 += 2 * dx; e2 += 2 * dy; z += stepZ;
                }
                return currentPos;
            }
        };
    }



    /**
     * 约束检查
     * @param p1 起点
     * @param p2 终点
     * @return true
     */
    private boolean isStraightPathPassable(BlockPos p1, BlockPos p2) {

        if (Math.abs(p1.getY() - p2.getY()) > MAX_STRAIGHTEN_ENDPOINT_Y_DIFF) {
            return false;
        }

        int lastY_Ground = -1;
        final int INVALID_HEIGHT = terrainData.getInvalidHeight();

        for (BlockPos posOnLine : bresenham3D(p1, p2)) {

            int x = posOnLine.getX();
            int y_math = posOnLine.getY();
            int z = posOnLine.getZ();

            int y_ground = terrainData.getSurfaceHeight(x, z);

            if (y_ground == INVALID_HEIGHT) {
                return false;
            }

            if (lastY_Ground != -1) {

                if (Math.abs(y_ground - lastY_Ground) > MAX_STEP_HEIGHT) {
                    return false;
                }
            }
            if (Math.abs(y_ground - y_math) > MAX_STEP_HEIGHT) {

                return false;
            }

            lastY_Ground = y_ground;
        }

        return true;
    }

    /**
     * 成本比较
     * 计算一条弯路在 startIndex 和 endIndex 之间的总路程。
     * @param path       路径列表
     * @param startIndex 起始点索引
     * @param endIndex   终止点索引
     * @return `dist(P1, P2) + dist(P2, P3)` 的总路程
     */
    private double calculatePathDistance(List<BlockPos> path, int startIndex, int endIndex) {
        double totalDistance = 0.0;
        for (int i = startIndex; i < endIndex; i++) {
            totalDistance += path.get(i).distSqr(path.get(i + 1));
        }
        return totalDistance;
    }
}