package euphy.upo.ceaselesscag.world.dynamic;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IRoadType;
import euphy.upo.ceaselesscag.world.builder.RoadBuilderManager;
import euphy.upo.ceaselesscag.world.builder.RoadBuilderRegistry;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.data.RoadNetworkData;
import euphy.upo.ceaselesscag.world.data.RoadSegment;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * 施工任务
 * 代表一个正在进行的、可分步执行的施工项目。
 */
public class BuildTask {

    public final RoadSegment segment;
    private final List<ConstructionPlan.ConstructionSegment> segmentsToBuild;
    private final RandomSource random;

    @Nullable
    private final IRoadType chosenRoadType;

    private int segmentIndex;
    private Iterator<BlockPos> bresenhamIterator;

    public BuildTask(RoadSegment segment, RoadNetworkData roadData, ServerLevel level) {
        this.segment = segment;
        this.segmentsToBuild = roadData.getConstructionSegments(segment);

        long seed = RandomSupport.generateUniqueSeed() ^ level.getSeed() ^ segment.hashCode();
        this.random = RandomSource.create(seed);

        this.segmentIndex = 0;
        setupNextBresenhamIterator();

        BuildContext tempContext = new BuildContext(
                level, null, BlockPos.ZERO, null, null,
                this.random,
                ConstructionPlan.ConstructionType.GROUND
        );

        Optional<IRoadType> chosenOpt = RoadBuilderRegistry.chooseRoadType(tempContext);
        this.chosenRoadType = chosenOpt.orElse(null);

        if (this.chosenRoadType == null) {
            CeaselessCaG.LOGGER.warn("[BuildTask] 无法为 {} 找到合适的道路类型！道路生成失败。", segment);
        }
    }


    private void setupNextBresenhamIterator() {
        if (segmentIndex < segmentsToBuild.size()) {
            ConstructionPlan.ConstructionSegment currentSegment = segmentsToBuild.get(segmentIndex);
            this.bresenhamIterator = RoadBuilderManager.bresenham3D(currentSegment.start(), currentSegment.end()).iterator();
        } else {
            this.bresenhamIterator = null;
        }
    }

    /**
     * 执行一步施工
     * @param level         主世界
     * @param blocksToPlace 这一步最多放置多少个方块
     * @return true
     */
    public boolean executeStep(ServerLevel level, int blocksToPlace) {

        if (isFinished() || this.chosenRoadType == null) {
            return true;
        }

        TerrainDataManager.TerrainData terrainData = TerrainDataManager.getInstance().getDataSnapshot();
        if (terrainData == null || !terrainData.isReady()) {
            return false;
        }

        for (int i = 0; i < blocksToPlace; i++) {

            if (bresenhamIterator == null || !bresenhamIterator.hasNext()) {
                segmentIndex++;
                if (isFinished()) {
                    return true;
                }
                setupNextBresenhamIterator();
                if (isFinished()) {
                    return true;
                }
            }

            BlockPos currentPos = bresenhamIterator.next();
            ConstructionPlan.ConstructionSegment currentSegment = segmentsToBuild.get(segmentIndex);

            BuildContext context = new BuildContext(
                    level,
                    null,
                    currentPos,
                    this.chosenRoadType,
                    terrainData,
                    random,
                    currentSegment.type()
            );

            RoadBuilderManager.buildBlockAt(
                    context,
                    currentSegment.start(),
                    currentSegment.end()
            );
        }

        return false;
    }

    public boolean isFinished() {
        return segmentIndex >= segmentsToBuild.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BuildTask buildTask = (BuildTask) o;
        return segment.equals(buildTask.segment);
    }

    @Override
    public int hashCode() {
        return segment.hashCode();
    }
}