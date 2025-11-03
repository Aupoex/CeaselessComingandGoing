package euphy.upo.ceaselesscag.world.terrain;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import euphy.upo.ceaselesscag.CeaselessCaG;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseSettings;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

/**
 * 地形数据管理器
 * 运行时持有所有地形高度数据。
 * 管理一个高性能、线程安全的 Caffeine 缓存，用于存储 ChunkPos -> short[16][16] 高度图。
 * 提供线程安全的 JIT 写入方法 `updateSingleChunkData`。
 * 提供高性能、线程安全的 JIT 读取方法 `getSurfaceHeight`。
 */
public class TerrainDataManager {

    /**
     * 缓存中允许存储的最大区块高度图数量。
     */
    private static final int MAX_CACHE_SIZE = 50000;

    private static final TerrainDataManager INSTANCE = new TerrainDataManager();

    /**
     * 当前持有的地形数据。
     */
    private volatile TerrainData currentData;

    private TerrainDataManager() {
        this.currentData = null;
    }

    public static TerrainDataManager getInstance() {
        return INSTANCE;
    }


    public void initialize(NoiseSettings noiseSettings) {
        if (this.currentData == null || !this.currentData.isReady()) {
            CeaselessCaG.LOGGER.info("[TerrainManager] 初始化 TerrainDataManager，最大缓存 {} 个区块...", MAX_CACHE_SIZE);
            this.currentData = new TerrainData(noiseSettings, MAX_CACHE_SIZE);
            CeaselessCaG.LOGGER.info("[TerrainManager] TerrainDataManager 已就绪 (JIT 模型)。");
        } else {
            CeaselessCaG.LOGGER.warn("[TerrainManager] TerrainDataManager 已被初始化，跳过。");
        }
    }

    public void shutdown() {
        if (this.currentData != null) {
            CeaselessCaG.LOGGER.info("[TerrainManager] 关闭 TerrainDataManager...");
            this.currentData.heightmapCache.invalidateAll();
            this.currentData = null;
            CeaselessCaG.LOGGER.info("[TerrainManager] TerrainDataManager 已关闭。");
        }
    }

    /**
     * JIT 写入方法
     * 向缓存中添加单个区块的高度图。
     * @param chunkId     区块的 Long ID
     * @param heightmap   从 TerrainHeightCalculator 计算出的 short[16][16] 高度图
     */
    public void updateSingleChunkData(long chunkId, short[][] heightmap) {
        final TerrainData data = this.currentData;

        if (data != null && data.isReady() && heightmap != null) {
            data.heightmapCache.put(chunkId, heightmap);
        }
    }

    /**
     * JIT 读取方法
     * 由 Pathfinders 和 PreConstructionPlanner 调用。
     * 从缓存中获取一个点的地表高度。
     * @param x 世界坐标 X
     * @param z 世界坐标 Z
     * @return 对应的地表 Y 坐标
     */
    public int getSurfaceHeight(int x, int z) {
        final TerrainData data = this.currentData;
        if (data != null) {
            return data.getSurfaceHeight(x, z);
        }
        return -65;
    }

    public boolean isDataReady() {
        final TerrainData data = this.currentData;
        return data != null && data.isReady();
    }

    /**
     * 获取当前 TerrainData 快照。
     */
    @Nullable
    public TerrainData getDataSnapshot() {
        return this.currentData;
    }



    public static class TerrainData {

        private final Cache<Long, short[][]> heightmapCache;

        private final NoiseSettings noiseSettings;
        private final int invalidHeight;

        public TerrainData(NoiseSettings noiseSettings, int cacheLimit) {
            this.noiseSettings = noiseSettings;

            this.heightmapCache = Caffeine.newBuilder()
                    .maximumSize(cacheLimit)
                    .build();

            if (noiseSettings != null) {
                this.invalidHeight = noiseSettings.minY() - 1;
                CeaselessCaG.LOGGER.debug("[TerrainData] 实例已创建。无效高度设为: {}", this.invalidHeight);
            } else {
                this.invalidHeight = -65;
                CeaselessCaG.LOGGER.error("[TerrainData] 传入的 NoiseSettings 为 null");
            }
        }


        public boolean isReady() {
            return this.heightmapCache != null && this.noiseSettings != null;
        }


        public int getInvalidHeight() {
            return invalidHeight;
        }


        public int getSurfaceHeight(int x, int z) {
            long chunkId = ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16));

            short[][] heightmap = heightmapCache.getIfPresent(chunkId);

            if (heightmap == null) {
                return this.invalidHeight;
            }

            try {
                int relX = Math.floorMod(x, 16);
                int relZ = Math.floorMod(z, 16);

                return heightmap[relX][relZ];

            } catch (ArrayIndexOutOfBoundsException e) {
                CeaselessCaG.LOGGER.error("[TerrainData] 高度图数组访问越界于 ({},{}), 区块 {}.", x, z, new ChunkPos(chunkId), e);
                return this.invalidHeight;
            }
        }

        public Set<Long> getKnownChunkIds() {
            return Collections.unmodifiableSet(heightmapCache.asMap().keySet());
        }

        public long getCurrentCacheSize() {
            return heightmapCache.estimatedSize();
        }


    }
}