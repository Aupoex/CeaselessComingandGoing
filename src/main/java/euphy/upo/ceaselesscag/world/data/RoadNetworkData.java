package euphy.upo.ceaselesscag.world.data;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.pathfinding.MacroPathSegment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 道路网络数据
 * 负责存储所有永久性的道路网络信息。
 */
public class RoadNetworkData extends SavedData {

    private static final String DATA_NAME = CeaselessCaG.MODID + "_RoadNetwork";

    private final Set<BlockPos> discoveredPois = ConcurrentHashMap.newKeySet();


    private final Set<Long> knownPoiScanChunks = ConcurrentHashMap.newKeySet();



    private final Set<RoadSegment> plannedRoads = ConcurrentHashMap.newKeySet();


    private final Map<RoadSegment, List<CompoundTag>> macroPathCache = new ConcurrentHashMap<>();


    private final Map<RoadSegment, List<BlockPos>> microPathBlueprint = new ConcurrentHashMap<>();


    /**
     * 存储已计算的施工分段指令。
     */
    private final Map<RoadSegment, List<CompoundTag>> constructionSegmentsCache = new ConcurrentHashMap<>();

    /**
     * 存储所有已成功建造的道路分段。
     */
    private final Set<RoadSegment> builtRoads = ConcurrentHashMap.newKeySet();

    /**
     * 瞬态反向查找表
     */
    private transient final Map<ChunkPos, Set<RoadSegment>> chunkToMicroPathLookup = new ConcurrentHashMap<>();



    public static final SavedData.Factory<RoadNetworkData> FACTORY = new SavedData.Factory<>(
            RoadNetworkData::new,
            RoadNetworkData::load,
            null
    );


    public RoadNetworkData() {
        super();
    }

    /**
     * JIT 扫描器 API
     * 尝试发现一个 POI。
     * @param pos 发现的 POI 坐标
     * @return true 一个新POI。
     */
    public boolean tryDiscoverPoi(BlockPos pos) {
        boolean isNewPoi = this.discoveredPois.add(pos);

        if (isNewPoi) {
            setDirty();
        }
        return isNewPoi;
    }

    /**
     * JIT 扫描器 API
     * 标记一个区块已被 POI 扫描器扫描过
     * @param chunkPos 已被扫描的区块
     */
    public void markChunkPoiScanned(ChunkPos chunkPos) {
        boolean isNewChunk = this.knownPoiScanChunks.add(chunkPos.toLong());

        if (isNewChunk) {
            setDirty();
        }
    }

    /**
     * JIT 扫描器 API
     * 获取所有已知已被扫描的区块 ID 的不可修改视图。
     * @return 一个线程安全的、不可修改的 Set 视图。
     */
    public Set<Long> getKnownPoiScanChunks() {
        return Collections.unmodifiableSet(knownPoiScanChunks);
    }


    /**
     * JIT 规划器 API
     * 获取所有已发现 POI的快照
     * @return 一个包含所有已发现 POI 的新 Set。
     */
    public Set<BlockPos> getAllDiscoveredPois() {
        return new HashSet<>(this.discoveredPois);
    }

    /**
     * [JIT 规划器 API]
     * 尝试添加一条新规划的道路。
     * @param segment 要添加的道路分段
     * @return true
     */
    public boolean addPlannedRoad(RoadSegment segment) {
        boolean isNewRoad = this.plannedRoads.add(segment);
        if (isNewRoad) {
            setDirty();
        }
        return isNewRoad;
    }

    /**
     * JIT 规划器 API
     * 检查一条道路是否已经被规划了。
     * @param segment 要检查的道路分段
     * @return true
     */
    public boolean isRoadPlanned(RoadSegment segment) {
        return this.plannedRoads.contains(segment);
    }


    /**
     * JIT 计算器 API
     * 检查一条道路的宏观路径是否已经被计算了。
     * @param segment 要检查的道路分段
     * @return true
     */
    public boolean isMacroPathCalculated(RoadSegment segment) {
        return this.macroPathCache.containsKey(segment);
    }

    /**
     * JIT 计算器 API
     * 检查一条道路的微观蓝图是否已经被计算了。
     * @param segment 要检查的道路分段
     * @return true
     */
    public boolean isMicroPathBlueprint(RoadSegment segment) {
        return this.microPathBlueprint.containsKey(segment);
    }

    /**
     * JIT 计算器 API
     * 将计算好的宏观路径存入数据库。
     * @param segment   道路分段
     * @param macroPath 宏观路径的 List
     */
    public synchronized void addMacroPath(RoadSegment segment, List<MacroPathSegment> macroPath) {
        if (macroPath == null || macroPath.isEmpty()) {
            return;
        }

        List<CompoundTag> tagList = new ArrayList<>(macroPath.size());
        for (MacroPathSegment macroSegment : macroPath) {
            tagList.add(macroSegment.save());
        }

        this.macroPathCache.put(segment, tagList);
        setDirty();
    }

    /**
     * JIT 计算器 API
     * 将计算好的微观路径蓝图存入数据库。
     * @param segment   道路分段
     * @param microPath 最终的、可建造的路径点列表 (拐点)
     * @return true
     */
    public synchronized boolean addMicroPathBlueprint(RoadSegment segment, List<BlockPos> microPath) {
        if (microPath == null || microPath.isEmpty()) return false;

        if (this.microPathBlueprint.putIfAbsent(segment, microPath) == null) {

            if (microPath.size() == 1) {
                addSegmentToChunkLookup(segment, new ChunkPos(microPath.get(0)));
            } else {
                for (int i = 0; i < microPath.size() - 1; i++) {

                    for (BlockPos posOnLine : bresenham3D(microPath.get(i), microPath.get(i + 1))) {
                        addSegmentToChunkLookup(segment, new ChunkPos(posOnLine));
                    }
                }
            }

            setDirty();
            return true;
        }

        return false;
    }

    /**
     * 将一个分段, 区块对添加到反向查找表】。
     */
    private void addSegmentToChunkLookup(RoadSegment segment, ChunkPos chunkPos) {
        this.chunkToMicroPathLookup.computeIfAbsent(chunkPos, k -> ConcurrentHashMap.newKeySet()).add(segment);
    }


    /**
     * 存储施工指令
     * @param segment 道路分段
     * @param segments 施工指令列表
     */
    public synchronized void addConstructionSegments(RoadSegment segment, List<ConstructionPlan.ConstructionSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        List<CompoundTag> tagList = new ArrayList<>(segments.size());
        for (ConstructionPlan.ConstructionSegment seg : segments) {
            tagList.add(writeConstructionSegment(seg));
        }
        this.constructionSegmentsCache.put(segment, tagList);
        setDirty();
    }

    /**
     * 获取施工指令
     * @param segment 道路分段
     * @return 施工指令列表
     */
    public synchronized List<ConstructionPlan.ConstructionSegment> getConstructionSegments(RoadSegment segment) {
        List<CompoundTag> tagList = this.constructionSegmentsCache.get(segment);
        if (tagList == null || tagList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConstructionPlan.ConstructionSegment> segments = new ArrayList<>(tagList.size());
        for (CompoundTag tag : tagList) {
            try {
                segments.add(readConstructionSegment(tag));
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[RoadNetworkData] 加载 ConstructionSegment 失败: {}", e.getMessage());
            }
        }
        return segments;
    }


    private CompoundTag writeConstructionSegment(ConstructionPlan.ConstructionSegment segment) {
        CompoundTag tag = new CompoundTag();
        tag.put("start", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, segment.start()).getOrThrow());
        tag.put("end", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, segment.end()).getOrThrow());
        tag.putString("type", segment.type().name());
        return tag;
    }

    private ConstructionPlan.ConstructionSegment readConstructionSegment(CompoundTag tag) {
        BlockPos start = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("start")).getOrThrow();
        BlockPos end = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("end")).getOrThrow();
        ConstructionPlan.ConstructionType type = ConstructionPlan.ConstructionType.valueOf(tag.getString("type"));
        return new ConstructionPlan.ConstructionSegment(start, end, type);
    }


    /**
     * 施工器写入
     * 将一个道路分段标记为已建造。
     * @param segment 要标记的分段
     */
    public synchronized void markSegmentAsBuilt(RoadSegment segment) {
        if (this.builtRoads.add(segment)) {
            CeaselessCaG.LOGGER.debug("[RoadNetworkData] 已将分段标记为【已建造】: {}", segment);
            setDirty();
        }
    }

    /**
     * 施工器读取
     * 检查一个道路分段是否已被建造。
     */
    public boolean isSegmentBuilt(RoadSegment segment) {
        return this.builtRoads.contains(segment);
    }

    /**
     * 施工器核心查询
     * 查找某个区块中所有蓝图就绪但未建造的道路分段。
     * @param chunkPos 要查询的区块
     * @return 一组需要追溯性建造的 `RoadSegment`
     */
    public Set<RoadSegment> findUnbuiltSegmentsInChunk(ChunkPos chunkPos) {

        Set<RoadSegment> segmentsInChunk = chunkToMicroPathLookup.get(chunkPos);
        if (segmentsInChunk == null || segmentsInChunk.isEmpty()) {
            return Collections.emptySet();
        }

        return segmentsInChunk.stream()
                .filter(segment -> !isSegmentBuilt(segment))
                .collect(Collectors.toSet());
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


    private static CompoundTag writeBlockPos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    private static BlockPos readBlockPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }



    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {

        ListTag poiList = new ListTag();
        for (BlockPos pos : discoveredPois) {
            poiList.add(writeBlockPos(pos));
        }
        nbt.put("DiscoveredPois", poiList);

        ListTag scannedChunksList = new ListTag();
        for (Long chunkId : knownPoiScanChunks) {
            scannedChunksList.add(LongTag.valueOf(chunkId));
        }
        nbt.put("KnownPoiScanChunks", scannedChunksList);

        ListTag roadList = new ListTag();
        for (RoadSegment segment : plannedRoads) {
            roadList.add(segment.save());
        }
        nbt.put("PlannedRoads", roadList);

        ListTag macroCacheList = new ListTag();
        macroPathCache.forEach((segment, pathTags) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.put("segment_key", segment.save());
            ListTag pathListTag = new ListTag();
            pathTags.forEach(pathListTag::add);
            entryTag.put("macro_path_value", pathListTag);
            macroCacheList.add(entryTag);
        });
        nbt.put("MacroPathCache", macroCacheList);

        ListTag microBlueprintList = new ListTag();
        microPathBlueprint.forEach((segment, pathPoints) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.put("segment_key", segment.save());
            ListTag pathListTag = new ListTag();
            for (BlockPos pos : pathPoints) {
                pathListTag.add(writeBlockPos(pos));
            }
            entryTag.put("micro_path_value", pathListTag);
            microBlueprintList.add(entryTag);
        });
        nbt.put("MicroPathBlueprint", microBlueprintList);

        ListTag constructionSegmentsList = new ListTag();
        constructionSegmentsCache.forEach((segment, segmentTags) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.put("segment_key", segment.save());
            ListTag segmentListTag = new ListTag();
            segmentTags.forEach(segmentListTag::add);
            entryTag.put("segments_value", segmentListTag);
            constructionSegmentsList.add(entryTag);
        });
        nbt.put("ConstructionSegmentsCache", constructionSegmentsList);

        ListTag builtRoadsList = new ListTag();
        builtRoads.forEach(segment -> builtRoadsList.add(segment.save()));
        nbt.put("BuiltRoads", builtRoadsList);

        CeaselessCaG.LOGGER.debug("[RoadNetworkData] 保存 ({} POIs, {} 已扫描区块, {} 已规划道路, {} 宏观, {} 微观)。",
                poiList.size(), scannedChunksList.size(), roadList.size(),
                macroCacheList.size(), microBlueprintList.size());
        return nbt;
    }


    public static RoadNetworkData load(CompoundTag nbt, HolderLookup.Provider registries) {
        RoadNetworkData data = new RoadNetworkData();

        ListTag poiList = nbt.getList("DiscoveredPois", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < poiList.size(); i++) {
            data.discoveredPois.add(readBlockPos(poiList.getCompound(i)));
        }

        ListTag scannedChunksList = nbt.getList("KnownPoiScanChunks", ListTag.TAG_LONG);
        for (int i = 0; i < scannedChunksList.size(); i++) {
            data.knownPoiScanChunks.add(((LongTag) scannedChunksList.get(i)).getAsLong());
        }

        ListTag roadList = nbt.getList("PlannedRoads", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < roadList.size(); i++) {
            try {
                data.plannedRoads.add(RoadSegment.load(roadList.getCompound(i)));
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[RoadNetworkData] 加载 PlannedRoads 时失败：无法解析 RoadSegment NBT。跳过此条目。", e);
            }
        }

        ListTag macroCacheList = nbt.getList("MacroPathCache", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < macroCacheList.size(); i++) {
            try {
                CompoundTag entryTag = macroCacheList.getCompound(i);
                RoadSegment segment = RoadSegment.load(entryTag.getCompound("segment_key"));
                ListTag valueList = entryTag.getList("macro_path_value", CompoundTag.TAG_COMPOUND);
                List<CompoundTag> macroPathNBT = new ArrayList<>();
                for (int j = 0; j < valueList.size(); j++) {
                    macroPathNBT.add(valueList.getCompound(j));
                }
                data.macroPathCache.put(segment, macroPathNBT);
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[RoadNetworkData] 加载 MacroPathCache 时失败：无法解析条目。跳过。", e);
            }
        }

        ListTag microBlueprintList = nbt.getList("MicroPathBlueprint", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < microBlueprintList.size(); i++) {
            try {
                CompoundTag entryTag = microBlueprintList.getCompound(i);
                RoadSegment segment = RoadSegment.load(entryTag.getCompound("segment_key"));

                ListTag pathListNBT = entryTag.getList("micro_path_value", CompoundTag.TAG_COMPOUND);
                List<BlockPos> path = new ArrayList<>(pathListNBT.size());
                for (int j = 0; j < pathListNBT.size(); j++) {
                    path.add(readBlockPos(pathListNBT.getCompound(j)));
                }

                data.microPathBlueprint.put(segment, path);

                if (path.size() == 1) {
                    data.addSegmentToChunkLookup(segment, new ChunkPos(path.get(0)));
                } else {
                    for (int j = 0; j < path.size() - 1; j++) {
                        for (BlockPos posOnLine : bresenham3D(path.get(j), path.get(j + 1))) {
                            data.addSegmentToChunkLookup(segment, new ChunkPos(posOnLine));
                        }
                    }
                }
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[RoadNetworkData] 加载 MicroPathBlueprint 时失败：无法解析条目。跳过。", e);
            }
        }

        ListTag constructionSegmentsList = nbt.getList("ConstructionSegmentsCache", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < constructionSegmentsList.size(); i++) {
            try {
                CompoundTag entryTag = constructionSegmentsList.getCompound(i);
                RoadSegment segment = RoadSegment.load(entryTag.getCompound("segment_key"));
                ListTag valueList = entryTag.getList("segments_value", CompoundTag.TAG_COMPOUND);
                List<CompoundTag> segmentsNBT = new ArrayList<>();
                for (int j = 0; j < valueList.size(); j++) {
                    segmentsNBT.add(valueList.getCompound(j));
                }
                data.constructionSegmentsCache.put(segment, segmentsNBT);
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[RoadNetworkData] 加载 ConstructionSegmentsCache 时失败：无法解析条目。跳过。", e);
            }
        }

        ListTag builtRoadsList = nbt.getList("BuiltRoads", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < builtRoadsList.size(); i++) {
            try {
                data.builtRoads.add(RoadSegment.load(builtRoadsList.getCompound(i)));
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[RoadNetworkData] 加载 BuiltRoads 时失败：跳过此条目。", e);
            }
        }

        CeaselessCaG.LOGGER.debug("[RoadNetworkData] 加载 ({} POIs, {} 已扫描区块, {} 已规划道路, {} 宏观, {} 微观)。",
                data.discoveredPois.size(), data.knownPoiScanChunks.size(), data.plannedRoads.size(),
                data.macroPathCache.size(), data.microPathBlueprint.size());
        return data;
    }

    public static RoadNetworkData get(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(FACTORY, DATA_NAME);
    }

}