package euphy.upo.ceaselesscag.world.builder.planning;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 施工规划
 * 是 `PreConstructionPlanner` 的最终输出。
 * 封装了所有关于如何建造一条道路的最终指令。
 * 被 `ArchitectManager` 接收，并用于更新 `RoadNetworkData` 和提交给施工器。
 */
public record ConstructionPlan(
        List<BlockPos> finalBlueprint,
        List<ConstructionSegment> segments
) {

    public record ConstructionSegment(

            BlockPos start,

            BlockPos end,

            ConstructionType type
    ) {}


    public enum ConstructionType {

        GROUND,

        NARROW_BRIDGE,

        WIDE_WATER_ROUTE,

        VIADUCT,

        TUNNEL
    }
}