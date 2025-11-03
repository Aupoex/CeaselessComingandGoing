package euphy.upo.ceaselesscag.world.terrain;

import euphy.upo.ceaselesscag.CeaselessCaG;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.Arrays;

/**
 * 地形高度计算器 。
 * 接收一个区块坐标和世界生成器信息，
 * 然后计算出该区块的精确地表高度图 (short[16][16])。
 * `TerrainHeightCalculator` 被 `DynamicRoadManager` 的 JIT 缓存工作线程在后台调用。
 * 计算结果将被传递给 `TerrainDataManager` 进行存储。
 */
public final class TerrainHeightCalculator {

    private TerrainHeightCalculator() {}

    /**
     * 同步计算单个区块的高度图，JIT 缓存系统的核心计算单元
     * @param chunkPos 要计算的区块坐标
     * @param noiseGen 区块生成器实例
     * @param randomState 世界的随机状态
     * @return 16x16 的地表高度图 (short[16][16])，如果计算失败则返回 null。
     */
    public static short[][] calculateHeightmapForSingleChunk(
            ChunkPos chunkPos,
            NoiseBasedChunkGenerator noiseGen,
            RandomState randomState) {

        try {
            final NoiseGeneratorSettings noiseGeneratorSettings = noiseGen.generatorSettings().value();
            final NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();

            final DensityFunction preciseDensity = randomState.router().finalDensity();
            final DensityFunction strippedPrecise = preciseDensity.mapAll(new StripInterpolatedVisitor());

            double[] densities = calculateDensitiesSteppedScan(
                    chunkPos, randomState, noiseSettings,
                    noiseGeneratorSettings, strippedPrecise
            );

            if (densities == null) {
                CeaselessCaG.LOGGER.warn("[TerrainCalculator] 密度计算返回 null，区块: {}.", chunkPos);
                return null;
            }
            return interpolateHeightFromDensities(densities, noiseSettings);

        } catch (Exception e) {
            CeaselessCaG.LOGGER.error("[TerrainCalculator] 计算单个区块高度图失败: {}，错误: {}", chunkPos, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 计算密度数组
     * 大步进小步进，高效计算一个区块所需的密度值。
     */
    private static double[] calculateDensitiesSteppedScan(
            ChunkPos chunkPos, RandomState randomState, NoiseSettings noiseSettings,
            NoiseGeneratorSettings generatorSettings, DensityFunction strippedPrecise
    ) {
        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();
        final int sizeY = Mth.floorDiv(noiseSettings.height(), cellHeight) + 1;
        final int sizeXZ = Mth.floorDiv(16, cellWidth) + 1;
        final double[] densities = new double[sizeXZ * sizeXZ * sizeY];

        final Aquifer.FluidPicker fluidPicker = (x, y, z) -> new Aquifer.FluidStatus(noiseSettings.minY() - 1, Blocks.AIR.defaultBlockState());

        final NoiseChunk noiseChunk = new NoiseChunk(
                sizeXZ - 1,
                randomState,
                chunkPos.getMinBlockX(),
                chunkPos.getMinBlockZ(),
                noiseSettings,
                DensityFunctions.BeardifierMarker.INSTANCE,
                generatorSettings,
                fluidPicker,
                Blender.empty()
        );

        final int step = 4;
        final int minCellY = Mth.floorDiv(noiseSettings.minY(), cellHeight);

        for (int cellLocalZ = 0; cellLocalZ < sizeXZ; cellLocalZ++) {
            for (int cellLocalX = 0; cellLocalX < sizeXZ; cellLocalX++) {

                int transitionTopY_idx = -1;
                double transitionTopDensity = -1.0;

                for (int cellLocalY_idx = sizeY - 1; cellLocalY_idx >= 0; cellLocalY_idx -= step) {
                    updateNoiseChunk(noiseChunk, chunkPos, cellLocalX, cellLocalY_idx, cellLocalZ, noiseSettings, minCellY);
                    double currentDensity = strippedPrecise.compute(noiseChunk);

                    if (currentDensity > 0) {
                        transitionTopY_idx = cellLocalY_idx + step;
                        break;
                    }
                    transitionTopDensity = currentDensity;
                }

                if (transitionTopY_idx == -1) {
                    int baseIndex = (cellLocalZ * sizeXZ + cellLocalX) * sizeY;
                    Arrays.fill(densities, baseIndex, baseIndex + sizeY, transitionTopDensity);
                    continue;
                }

                transitionTopY_idx = Math.min(sizeY - 1, transitionTopY_idx);
                int precisionBottomY_idx = Math.max(0, transitionTopY_idx - step);

                double lastDensity = transitionTopDensity;
                for (int cellLocalY_idx = transitionTopY_idx; cellLocalY_idx >= precisionBottomY_idx; cellLocalY_idx--) {
                    updateNoiseChunk(noiseChunk, chunkPos, cellLocalX, cellLocalY_idx, cellLocalZ, noiseSettings, minCellY);
                    lastDensity = strippedPrecise.compute(noiseChunk);
                    int index = (cellLocalZ * sizeXZ + cellLocalX) * sizeY + cellLocalY_idx;
                    densities[index] = lastDensity;
                }

                int baseIndex = (cellLocalZ * sizeXZ + cellLocalX) * sizeY;
                Arrays.fill(densities, baseIndex + transitionTopY_idx + 1, baseIndex + sizeY, transitionTopDensity);
                Arrays.fill(densities, baseIndex, baseIndex + precisionBottomY_idx, lastDensity);
            }
        }
        return densities;
    }


    /**
     * 从密度插值高度
     */
    private static short[][] interpolateHeightFromDensities(double[] densities, NoiseSettings noiseSettings) {
        short[][] heightmap = new short[16][16];

        final int invalidHeight = noiseSettings.minY() - 1;
        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();
        final int sizeY = Mth.floorDiv(noiseSettings.height(), cellHeight) + 1;
        final int sizeXZ = Mth.floorDiv(16, cellWidth) + 1;
        final int minCellY = Mth.floorDiv(noiseSettings.minY(), cellHeight);

        final int expectedLength = sizeXZ * sizeXZ * sizeY;
        if (densities.length != expectedLength) {
            CeaselessCaG.LOGGER.error("[TerrainCalculator] 密度数组长度不匹配，预期 {}, 得到 {}. 无法插值高度。", expectedLength, densities.length);
            for (short[] row : heightmap) Arrays.fill(row, (short)invalidHeight);
            return heightmap;
        }

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {

                int cellX = localX / cellWidth;
                int cellZ = localZ / cellWidth;
                double fracX = (double) (localX % cellWidth) / cellWidth;
                double fracZ = (double) (localZ % cellWidth) / cellWidth;

                boolean foundHeight = false;

                for (int cellY = sizeY - 2; cellY >= 0; --cellY) {
                    int top_idx = cellY + 1;
                    int bottom_idx = cellY;

                    try {
                        double d001 = densities[((cellZ * sizeXZ + cellX) * sizeY) + top_idx];
                        double d101 = densities[((cellZ * sizeXZ + (cellX + 1)) * sizeY) + top_idx];
                        double d011 = densities[(((cellZ + 1) * sizeXZ + cellX) * sizeY) + top_idx];
                        double d111 = densities[(((cellZ + 1) * sizeXZ + (cellX + 1)) * sizeY) + top_idx];
                        double topDensity = Mth.lerp2(fracX, fracZ, d001, d101, d011, d111);

                        if (topDensity <= 0) {
                            double d000 = densities[((cellZ * sizeXZ + cellX) * sizeY) + bottom_idx];
                            double d100 = densities[((cellZ * sizeXZ + (cellX + 1)) * sizeY) + bottom_idx];
                            double d010 = densities[(((cellZ + 1) * sizeXZ + cellX) * sizeY) + bottom_idx];
                            double d110 = densities[(((cellZ + 1) * sizeXZ + (cellX + 1)) * sizeY) + bottom_idx];
                            double bottomDensity = Mth.lerp2(fracX, fracZ, d000, d100, d010, d110);

                            if (bottomDensity > 0) {
                                int cellBottomWorldY = (minCellY + cellY) * cellHeight;
                                double t = Mth.inverseLerp(0.0, bottomDensity, topDensity);
                                double calculatedY = cellBottomWorldY + (t * cellHeight);
                                heightmap[localX][localZ] = (short) Math.round(calculatedY);
                                foundHeight = true;
                                break;
                            }
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                        CeaselessCaG.LOGGER.error("[TerrainCalculator] 高度插值 AIOOBE 索引越界于 cell ({},{},{}) coord ({},{})", cellX, cellY, cellZ, localX, localZ);
                        break;
                    }
                }

                if (!foundHeight) {
                    heightmap[localX][localZ] = (short) invalidHeight;
                }
            }
        }

        return heightmap;
    }


    /**
     * 更新可重用的 NoiseChunk 的位置
     */
    private static void updateNoiseChunk(NoiseChunk noiseChunk, ChunkPos chunkPos, int cellX, int cellY_idx, int cellZ, NoiseSettings settings, int minCellY) {

        noiseChunk.cellStartBlockX = chunkPos.getMinBlockX() + cellX * settings.getCellWidth();
        noiseChunk.cellStartBlockY = (minCellY + cellY_idx) * settings.getCellHeight();
        noiseChunk.cellStartBlockZ = chunkPos.getMinBlockZ() + cellZ * settings.getCellWidth();
        noiseChunk.inCellX = 0;
        noiseChunk.inCellY = 0;
        noiseChunk.inCellZ = 0;
    }

    /**
     * 剥离原版噪声函数中插值器的访问者类
     */
    private static class StripInterpolatedVisitor implements DensityFunction.Visitor {
        @Override
        public DensityFunction apply(DensityFunction function) {
            if (function instanceof DensityFunctions.Marker marker) {
                if (marker.type() == DensityFunctions.Marker.Type.Interpolated) {
                    return marker.wrapped().mapAll(this);
                }
            }
            if (function instanceof DensityFunctions.HolderHolder holderHolder) {
                return holderHolder.function().value().mapAll(this);
            }
            return function;
        }
    }
}