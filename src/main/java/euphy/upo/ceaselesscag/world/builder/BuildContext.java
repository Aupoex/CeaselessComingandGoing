package euphy.upo.ceaselesscag.world.builder;

import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;
import euphy.upo.ceaselesscag.CeaselessCaG;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 施工上下文
 * 在施工时由施工管理器创建。
 * 被传递给每一个`IBuilderComponent`。携带了来自 `PreConstructionPlanner`的最终施工指令`ConstructionType`，
 */
public class BuildContext {
    private final ServerLevel level;

    @Nullable private final WorldGenRegion region;

    private final BlockPos currentPos;
    @Nullable private final IRoadType roadType;
    @Nullable private final TerrainDataManager.TerrainData terrainData;
    @Nullable private final RandomSource random;


    private final ConstructionPlan.ConstructionType constructionType;

    public BuildContext(
            ServerLevel level,
            @Nullable WorldGenRegion region,
            BlockPos currentPos,
            @Nullable IRoadType roadType,
            @Nullable TerrainDataManager.TerrainData terrainData,
            @Nullable RandomSource random,
            ConstructionPlan.ConstructionType constructionType
    ) {
        this.level = level;
        this.region = region;
        this.currentPos = currentPos;
        this.roadType = roadType;
        this.terrainData = terrainData;
        this.random = random;
        this.constructionType = constructionType;
    }

    public ServerLevel getLevel() { return level; }
    public BlockPos getCurrentPos() { return currentPos; }
    @Nullable public IRoadType getRoadType() { return roadType; }
    @Nullable public TerrainDataManager.TerrainData getTerrainData() { return terrainData; }
    @Nullable public RandomSource getRandom() { return random; }

    public ConstructionPlan.ConstructionType getConstructionType() {
        return (this.constructionType != null) ? this.constructionType : ConstructionPlan.ConstructionType.GROUND;
    }



    public Direction getApproximateDirection(BlockPos segmentStart, BlockPos segmentEnd) {
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
     * 施工 API - 在 currentPos + relativePos 处放置方块。
     */
    public boolean setBlock(BlockPos relativePos, BlockState state) {
        BlockPos worldPos = currentPos.offset(relativePos);

        try {
            if (!level.isInWorldBounds(worldPos)) {
                return false;
            }

            if (region != null) {
                return region.setBlock(worldPos, state, 2);
            } else {
                return level.setBlock(worldPos, state, 3);
            }

        } catch (Exception e) {
            CeaselessCaG.LOGGER.error("[BuildContext] 放置方块时出错于 {}: {}", worldPos, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 施工 API - 获取 currentPos + relativePos 处的方块状态。
     */
    @Nullable
    public BlockState getBlockState(BlockPos relativePos) {
        BlockPos worldPos = currentPos.offset(relativePos);
        try {
            if (level.isInWorldBounds(worldPos)) {
                return (region != null) ? region.getBlockState(worldPos) : level.getBlockState(worldPos);
            }
        } catch (Exception e) {
            CeaselessCaG.LOGGER.error("[BuildContext] 获取方块状态时出错于 {}: {}", worldPos, e.getMessage());
        }
        return null;
    }
}