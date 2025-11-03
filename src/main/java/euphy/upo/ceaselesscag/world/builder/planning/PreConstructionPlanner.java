package euphy.upo.ceaselesscag.world.builder.planning;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.RoadBuilderManager;
import euphy.upo.ceaselesscag.world.pathfinding.InterpolationSegment;
import euphy.upo.ceaselesscag.world.pathfinding.InterpolationType;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 施工前规划器
 */
public final class PreConstructionPlanner {

    private PreConstructionPlanner() {}

    private static final int WIDE_WATER_THRESHOLD_SQR = 80 * 80;

    /**
     * API - 创建最终的施工计划。
     */
    public static ConstructionPlan createPlan(
            ServerLevel level,
            TerrainDataManager.TerrainData terrainData,
            List<InterpolationSegment> macroPath,
            List<BlockPos> denseMicroPath
    ) {
        if (denseMicroPath.isEmpty()) {
            return new ConstructionPlan(new ArrayList<>(), new ArrayList<>());
        }

        List<BlockPos> finalBlueprint = new ArrayList<>();
        List<ConstructionPlan.ConstructionSegment> finalSegments = new ArrayList<>();
        final int seaLevel = level.getChunkSource().getGenerator().getSeaLevel();

        Map<BlockPos, InterpolationSegment> straightLineStarts = new HashMap<>();
        for (InterpolationSegment seg : macroPath) {
            if (seg.type() == InterpolationType.STRAIGHT_LINE) {
                straightLineStarts.put(seg.start(), seg);
            }
        }

        int segmentStartIndex = 0;
        BlockPos segmentStartPoint = denseMicroPath.get(0);
        ConstructionPlan.ConstructionType segmentType = getPhysicalType(segmentStartPoint, level, terrainData);

        BlockPos correctedStart = correctCoordinates(
                segmentStartPoint,
                segmentType,
                segmentType,
                seaLevel,
                segmentStartPoint
        );
        finalBlueprint.add(correctedStart);
        segmentStartPoint = correctedStart;

        for (int i = 1; i < denseMicroPath.size(); i++) {

            BlockPos currentPoint = denseMicroPath.get(i);
            ConstructionPlan.ConstructionType currentType;

            InterpolationSegment straightLine = straightLineStarts.get(currentPoint);
            boolean isLastPoint = (i == denseMicroPath.size() - 1);

            if (straightLine != null) {
                currentType = classifyStraightLine(straightLine.start(), straightLine.end(), terrainData, level, seaLevel);
            } else {
                currentType = getPhysicalType(currentPoint, level, terrainData);
            }

            if (currentType != segmentType || straightLine != null || isLastPoint) {

                int segmentEndIndex = i - 1;
                if (isLastPoint && currentType == segmentType && straightLine == null) {
                    segmentEndIndex = i;
                }
                BlockPos segmentEndPoint = denseMicroPath.get(segmentEndIndex);

                BlockPos correctedEnd = correctCoordinates(
                        segmentEndPoint,
                        segmentType,
                        currentType,
                        seaLevel,
                        segmentStartPoint
                );

                if (segmentType == ConstructionPlan.ConstructionType.GROUND) {
                    BlockPos lastCorrectedPoint = segmentStartPoint;

                    for (int j = segmentStartIndex + 1; j <= segmentEndIndex; j++) {
                        BlockPos thisPoint = denseMicroPath.get(j);

                        boolean isThisTheLastGroundPoint = (j == segmentEndIndex);

                        ConstructionPlan.ConstructionType thisNextType = isThisTheLastGroundPoint ?
                                currentType :
                                segmentType;

                        BlockPos thisCorrectedPoint = correctCoordinates(
                                thisPoint,
                                segmentType,
                                thisNextType,
                                seaLevel,
                                segmentStartPoint
                        );

                        if (!lastCorrectedPoint.equals(thisCorrectedPoint)) {
                            finalBlueprint.add(thisCorrectedPoint);
                            finalSegments.add(new ConstructionPlan.ConstructionSegment(lastCorrectedPoint, thisCorrectedPoint, segmentType));
                            lastCorrectedPoint = thisCorrectedPoint;
                        }
                    }

                    segmentStartPoint = lastCorrectedPoint;

                } else {
                    if (!segmentStartPoint.equals(correctedEnd)) {
                        if (segmentType == ConstructionPlan.ConstructionType.NARROW_BRIDGE && segmentStartPoint.distSqr(correctedEnd) > WIDE_WATER_THRESHOLD_SQR) {
                            segmentType = ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE;
                        }

                        for(BlockPos pos : RoadBuilderManager.bresenham3D(segmentStartPoint, correctedEnd)) {
                            if (!pos.equals(segmentStartPoint)) finalBlueprint.add(pos);
                        }
                        finalSegments.add(new ConstructionPlan.ConstructionSegment(segmentStartPoint, correctedEnd, segmentType));
                        segmentStartPoint = correctedEnd;
                    }
                }
                segmentStartIndex = i;

                if (straightLine != null) {
                    segmentType = currentType;
                    segmentStartPoint = correctedEnd;

                    segmentEndPoint = straightLine.end();

                    ConstructionPlan.ConstructionType nextType = (i >= denseMicroPath.size() - 1) ?
                            segmentType : getPhysicalType(denseMicroPath.get(i + 1), level, terrainData);

                    correctedEnd = correctCoordinates(segmentEndPoint, segmentType, nextType, seaLevel, segmentStartPoint);

                    if (!segmentStartPoint.equals(correctedEnd)) {
                        for(BlockPos pos : RoadBuilderManager.bresenham3D(segmentStartPoint, correctedEnd)) {
                            if (!pos.equals(segmentStartPoint)) finalBlueprint.add(pos);
                        }
                        finalSegments.add(new ConstructionPlan.ConstructionSegment(segmentStartPoint, correctedEnd, segmentType));
                    }

                    while (i < denseMicroPath.size() - 1 && !denseMicroPath.get(i).equals(segmentEndPoint)) {
                        i++;
                    }

                    segmentStartIndex = i;
                    segmentStartPoint = correctedEnd;
                }

                if(segmentStartIndex < denseMicroPath.size()) {
                    segmentType = getPhysicalType(denseMicroPath.get(segmentStartIndex), level, terrainData);
                }
            }
        }


        return new ConstructionPlan(finalBlueprint, finalSegments);
    }

    /**
     * 修正单个点的坐标
     */
    private static BlockPos correctCoordinates(
            BlockPos pos,
            ConstructionPlan.ConstructionType ownType,
            ConstructionPlan.ConstructionType nextType,
            int seaLevel,
            BlockPos segmentStartPoint
    ) {
        if (ownType == ConstructionPlan.ConstructionType.GROUND &&
                (nextType == ConstructionPlan.ConstructionType.NARROW_BRIDGE || nextType == ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE)) {
            return pos.atY(seaLevel + 1);
        }
        if ((ownType == ConstructionPlan.ConstructionType.NARROW_BRIDGE || ownType == ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE) &&
                nextType == ConstructionPlan.ConstructionType.GROUND) {
            return pos.atY(seaLevel + 1);
        }

        switch (ownType) {
            case NARROW_BRIDGE:
            case WIDE_WATER_ROUTE:
                return pos.atY(seaLevel + 1);
            case VIADUCT:
                return pos.atY(segmentStartPoint.getY());
            case GROUND:
            case TUNNEL:
            default:
                return pos;
        }
    }


    /**
     * 逐点物理分类
     */
    private static ConstructionPlan.ConstructionType getPhysicalType(
            BlockPos pos,
            ServerLevel level,
            TerrainDataManager.TerrainData terrainData
    ) {
        if (isWaterEnvironment(pos, level)) {
            return ConstructionPlan.ConstructionType.NARROW_BRIDGE;
        }

        return ConstructionPlan.ConstructionType.GROUND;
    }


    /**
     * 分类直线分段。
     */
    private static ConstructionPlan.ConstructionType classifyStraightLine(
            BlockPos p1, BlockPos p2,
            TerrainDataManager.TerrainData terrainData,
            ServerLevel level, int seaLevel
    ) {
        BlockPos midPoint = p1.offset(
                (p2.getX() - p1.getX()) / 2,
                (p2.getY() - p1.getY()) / 2,
                (p2.getZ() - p1.getZ()) / 2
        );

        if (isWaterEnvironment(midPoint, level)) {
            if (p1.atY(seaLevel+1).distSqr(p2.atY(seaLevel+1)) > WIDE_WATER_THRESHOLD_SQR) {
                return ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE;
            } else {
                return ConstructionPlan.ConstructionType.NARROW_BRIDGE;
            }
        }

        int surfaceY = terrainData.getSurfaceHeight(midPoint.getX(), midPoint.getZ());
        if (surfaceY != terrainData.getInvalidHeight() && midPoint.getY() < surfaceY - 5) {
            return ConstructionPlan.ConstructionType.TUNNEL;
        }

        return ConstructionPlan.ConstructionType.VIADUCT;
    }

    /**
     *  检查坐标是否在水中或在水底。
     */

    private static boolean isWaterEnvironment(BlockPos pos, ServerLevel level) {
        if (isWater(level.getFluidState(pos))) return true;
        if (isWater(level.getFluidState(pos.below()))) return true;
        if (isWater(level.getFluidState(pos.above()))) return true;
        return false;
    }

    /**
     * 检查流体是否是水。
     */
    private static boolean isWater(FluidState fluidState) {
        return fluidState.is(Fluids.WATER) || fluidState.is(Fluids.FLOWING_WATER);
    }
}