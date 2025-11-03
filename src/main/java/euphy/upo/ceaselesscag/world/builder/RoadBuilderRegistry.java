package euphy.upo.ceaselesscag.world.builder;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.roadtypes.DefaultRoadTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 道路建造注册表
 * 注册并管理所有的 `IRoadType`
 */
public final class RoadBuilderRegistry {

    private RoadBuilderRegistry() {}

    private static final Map<ResourceLocation, IRoadType> ROAD_TYPES = new ConcurrentHashMap<>();

    /**
     * API
     * 注册一个新的道路类型。
     */
    public static void registerRoadType(IRoadType roadType) {
        if (roadType == null || roadType.getId() == null) {
            CeaselessCaG.LOGGER.error("[RoadRegistry] 尝试注册一个 null 或 ID 为 null 的道路类型！");
            return;
        }
        if (ROAD_TYPES.containsKey(roadType.getId())) {
            CeaselessCaG.LOGGER.warn("[RoadRegistry] 道路类型 {} 已被注册。正在覆盖...", roadType.getId());
        }
        ROAD_TYPES.put(roadType.getId(), roadType);
        CeaselessCaG.LOGGER.debug("[RoadRegistry] 已注册道路类型: {}", roadType.getId());
    }

    /**
     * 施工 API
     * 根据当前的`BuildContext`选择最合适的 `IRoadType`。
     * @param context 当前的施工上下文
     * @return 最合适的道路类型 (IRoadType)
     */
    public static Optional<IRoadType> chooseRoadType(BuildContext context) {
        return ROAD_TYPES.values().stream()
                .filter(type -> type.getSpawnCondition().test(context))
                .max(Comparator.comparingInt(IRoadType::getPriority));
    }


    public static void registerDefaults() {

        registerRoadType(DefaultRoadTypes.STONE_BRICK_ROAD);
        registerRoadType(DefaultRoadTypes.DEEPSLATE_ROAD);
    }

}