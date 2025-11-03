package euphy.upo.ceaselesscag.world.builder;

import euphy.upo.ceaselesscag.CeaselessCaG;

import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 道路建造管理器
 * 确保按道路正确顺序组装
 */
public final class RoadBuilderManager {

    private RoadBuilderManager() {}

    /**
     * 组装顺序。
     * 施工器将按照这个顺序调用组件。
     */
    public static final List<IBuilderComponent.ComponentType> BUILD_ORDER = List.of(
            IBuilderComponent.ComponentType.FOUNDATION,  // 1. 路基 (清理/填充)
            IBuilderComponent.ComponentType.SUPPORT,     // 2. 支撑 (桥墩/隧道)
            IBuilderComponent.ComponentType.SURFACE,     // 3. 路面
            IBuilderComponent.ComponentType.RAILING,     // 4. 护栏
            IBuilderComponent.ComponentType.LIGHTING,    // 5. 照明
            IBuilderComponent.ComponentType.DECORATION   // 6. 装饰
    );

    /**
     * 施工 API - 在单个点上执行建造。
     * @param context      施工说明书 ，包含 `currentPos` 和 `ConstructionType`
     * @param segmentStart 当前施工段的起点
     * @param segmentEnd   当前施工段的终点
     */
    public static void buildBlockAt(
            BuildContext context,
            BlockPos segmentStart,
            BlockPos segmentEnd
    ) {

        IRoadType chosenRoadType = context.getRoadType();

        if (chosenRoadType == null) {
            return;
        }

        var components = chosenRoadType.getComponents();

        for (IBuilderComponent.ComponentType componentType : BUILD_ORDER) {
            IBuilderComponent component = components.get(componentType);

            if (component != null && component.shouldPlace(context)) {
                try {
                    component.place(context, segmentStart, segmentEnd);
                } catch (Exception e) {
                    CeaselessCaG.LOGGER.error(
                            "[RoadBuilder] 组件 {} (来自 {}) 在 {} 放置时出错: {}",
                            component.getId(), chosenRoadType.getId(),
                            context.getCurrentPos(), e.getMessage()
                    );
                }
            }
        }
    }

    /**
     * 3D Bresenham 直线算法迭代器。
     */
    public static Iterable<BlockPos> bresenham3D(BlockPos p1, BlockPos p2) {
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