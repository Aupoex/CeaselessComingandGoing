package euphy.upo.ceaselesscag.world.architect;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.data.RoadNetworkData;
import euphy.upo.ceaselesscag.world.data.RoadSegment;
import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 道路网络规划器
 * 决定谁连接谁的网络拓扑。
 */
public final class RoadNetworkPlanner {


    private RoadNetworkPlanner() {}

    /**
     * 每个新发现的 POI 尝试连接到的现有 POI的最大数量。
     * 1 = 树状结构，最少连接。
     * 2 = 允许一些冗余和环路。
     */
    private static final int MAX_CONNECTIONS_PER_NEW_POI = 2;

    /**
     * 增量式规划。
     * @param newlyFoundPois 刚刚在 JIT 阶段发现的新 POI 集合
     * @param roadData       道路网络的“永久数据库”
     * @return 包含所有新规划的道路分段 (RoadSegment) Set
     */
    public static Set<RoadSegment> planIncremental(Set<BlockPos> newlyFoundPois, RoadNetworkData roadData) {

        Set<BlockPos> allPois = roadData.getAllDiscoveredPois();

        if (newlyFoundPois.isEmpty() || allPois.size() < 2) {
            return Set.of();
        }


        List<BlockPos> existingPois = allPois.stream()
                .filter(poi -> !newlyFoundPois.contains(poi))
                .toList();


        if (existingPois.isEmpty()) {
            return Set.of();
        }

        CeaselessCaG.LOGGER.info("[RoadPlanner] 将 {} 个新 POI 连接到 {} 个现有 POI...", newlyFoundPois.size(), existingPois.size());

        Set<RoadSegment> newSegments = new HashSet<>();

        for (BlockPos newPoi : newlyFoundPois) {

            List<BlockPos> closestExistingPois = existingPois.stream()
                    .sorted(Comparator.comparingDouble(existingPoi -> existingPoi.distSqr(newPoi)))
                    .limit(MAX_CONNECTIONS_PER_NEW_POI)
                    .toList();

            int connectionsMade = 0;
            for (BlockPos existingPoi : closestExistingPois) {
                if (connectionsMade >= MAX_CONNECTIONS_PER_NEW_POI) {
                    break;
                }

                RoadSegment newSegment = new RoadSegment(newPoi, existingPoi);

                if (roadData.isRoadPlanned(newSegment)) {
                    continue;
                }


                if (roadData.addPlannedRoad(newSegment)) {
                    newSegments.add(newSegment);
                    connectionsMade++;
                }
            }
        }

        CeaselessCaG.LOGGER.info("[RoadPlanner] 规划完毕，创建了 {} 条新路。", newSegments.size());
        return newSegments;
    }
}