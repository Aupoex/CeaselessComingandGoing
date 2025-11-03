package euphy.upo.ceaselesscag.world.pathfinding;

import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 微观路径寻路器
 * 计算工具，插值器。
 * 接收 `MjpsPathfinder` 输出的 `List<InterpolationSegment>`。
 * 遍历分段列表，根据 `InterpolationType` 来决定如何填充拐点之间的空白。
 * - `STRAIGHT_LINE`: 使用 Bresenham 直线插值。
 * - `GROUND_SMOOTH`: 使用 `CatmullRom` 样条曲线插值。
 * 输出一个密集的`List<BlockPos>`，包含路径上的方块。
 */
public final class MicroPathfinder {


    private MicroPathfinder() {}

    /**
     * Catmull-Rom 样条曲线的插值密度。
     * 值越高，曲线越平滑，计算量越大。
     */
    private static final double CATMULL_ROM_DENSITY = 2.0;

    public static List<BlockPos> interpolatePath(
            List<InterpolationSegment> macroPath,
            TerrainDataManager.TerrainData terrainData
    ) {
        List<BlockPos> finalPath = new ArrayList<>();
        if (macroPath == null || macroPath.isEmpty()) {
            return finalPath;
        }

        BlockPos p0, p1, p2, p3;

        for (int i = 0; i < macroPath.size(); i++) {
            InterpolationSegment segment = macroPath.get(i);

            List<BlockPos> segmentPoints;
            if (segment.type() == InterpolationType.STRAIGHT_LINE) {
                segmentPoints = interpolateStraightLine(segment.start(), segment.end());

            } else {
                p1 = segment.start();
                p2 = segment.end();
                p0 = (i == 0) ? p1 : macroPath.get(i - 1).start();
                p3 = (i == macroPath.size() - 1) ? p2 : macroPath.get(i + 1).end();

                segmentPoints = interpolateGroundSmooth(p0, p1, p2, p3, terrainData);            }

            if (finalPath.isEmpty()) {
                finalPath.addAll(segmentPoints);
            } else if (!segmentPoints.isEmpty()) {
                finalPath.addAll(segmentPoints.subList(1, segmentPoints.size()));
            }
        }
        return finalPath;
    }

    private static List<BlockPos> interpolateGroundSmooth(
            BlockPos p0, BlockPos p1, BlockPos p2, BlockPos p3,
            TerrainDataManager.TerrainData terrainData
    ) {
        List<BlockPos> points = new ArrayList<>();
        BlockPos lastPos = null;

        double distance = Math.sqrt(p1.distSqr(p2));
        int steps = (int) Math.max(1, distance * CATMULL_ROM_DENSITY);

        final int INVALID_HEIGHT = terrainData.getInvalidHeight();

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;

            double x = CatmullRom.interpolate(p0.getX(), p1.getX(), p2.getX(), p3.getX(), t);
            double z = CatmullRom.interpolate(p0.getZ(), p1.getZ(), p2.getZ(), p3.getZ(), t);

            int roundedX = (int) Math.round(x);
            int roundedZ = (int) Math.round(z);

            int roundedY = terrainData.getSurfaceHeight(roundedX, roundedZ);

            if (roundedY == INVALID_HEIGHT) {
                continue;
            }

            BlockPos currentPos = new BlockPos(roundedX, roundedY, roundedZ);

            if (lastPos == null || !currentPos.equals(lastPos)) {
                points.add(currentPos);
                lastPos = currentPos;
            }
        }
        if (lastPos == null) {
            points.add(p1);
        }
        if (lastPos != null && !lastPos.equals(p2)) {
            points.add(p2);
        }

        return points;
    }

    /**
     *  Bresenham 直线插值
     */
    private static List<BlockPos> interpolateStraightLine(BlockPos p1, BlockPos p2) {
        List<BlockPos> points = new ArrayList<>();
        for (BlockPos posOnLine : bresenham3D(p1, p2)) {
            points.add(posOnLine);
        }
        return points;
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
}