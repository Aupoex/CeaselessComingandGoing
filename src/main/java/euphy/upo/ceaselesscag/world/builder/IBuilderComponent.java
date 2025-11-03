package euphy.upo.ceaselesscag.world.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 施工组件接口
 * 定义一个单一的、可组合的施工模块
 */
public interface IBuilderComponent {


    ResourceLocation getId();

    ComponentType getType();

    /**
     * 检查在当前上下文中是否应该放置此组件。
     * @param context 施工说明书
     *
     */
    boolean shouldPlace(BuildContext context);

    /**
     * 执行放置
     * @param context 施工说明书
     * @param segmentStart 当前施工段的起点
     * @param segmentEnd 当前施工段的终点
     */
    void place(BuildContext context, BlockPos segmentStart, BlockPos segmentEnd);

    /**
     * 组件类型。
     */
    enum ComponentType {
        // 组件
        FOUNDATION,  // 路基准备 (清理、填充)
        SUPPORT,     // 结构支撑 (桥墩)
        SURFACE,     // 路面铺设

        // 可选组件
        RAILING,     // 护栏
        LIGHTING,    // 照明
        DECORATION   // 装饰物
    }
}