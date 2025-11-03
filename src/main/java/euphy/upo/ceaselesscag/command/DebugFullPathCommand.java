package euphy.upo.ceaselesscag.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.architect.ArchitectManager;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.builder.planning.PreConstructionPlanner;
import euphy.upo.ceaselesscag.world.pathfinding.InterpolationSegment;
import euphy.upo.ceaselesscag.world.pathfinding.MicroPathfinder;
import euphy.upo.ceaselesscag.world.pathfinding.MjpsPathfinder;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 完整路径调试命令
 */
public class DebugFullPathCommand {

    private record CalculationResult(
            List<InterpolationSegment> macroPath,
            List<BlockPos> denseMicroPath
    ) {}

    private record VisualizationPackage(
            ConstructionPlan plan,
            List<BlockPos> denseMicroPath
    ) {}


    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ccg_debug_full_path")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(DebugFullPathCommand::execute)
                                        )
                                )
                        )
                )
        );
    }


    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();

        final int x1 = IntegerArgumentType.getInteger(context, "x1");
        final int z1 = IntegerArgumentType.getInteger(context, "z1");
        final int x2 = IntegerArgumentType.getInteger(context, "x2");
        final int z2 = IntegerArgumentType.getInteger(context, "z2");

        final TerrainDataManager.TerrainData terrainData = TerrainDataManager.getInstance().getDataSnapshot(); //
        if (terrainData == null || !TerrainDataManager.getInstance().isDataReady()) {
            source.sendFailure(Component.literal("TerrainDataManager 未就绪。"));
            return 0;
        }

        final int y1 = terrainData.getSurfaceHeight(x1, z1);
        final int y2 = terrainData.getSurfaceHeight(x2, z2);
        final int INVALID_HEIGHT = terrainData.getInvalidHeight();
        if (y1 == INVALID_HEIGHT || y2 == INVALID_HEIGHT) {
            source.sendFailure(Component.literal("起点或终点不在缓存区域内。"));
            return 0;
        }

        final BlockPos startPos = new BlockPos(x1, y1, z1);
        final BlockPos endPos = new BlockPos(x2, y2, z2);

        source.sendSuccess(() -> Component.literal(
                String.format("正在计算路径 %s 到 %s ...",
                        startPos.toShortString(), endPos.toShortString())
        ), true);

        CompletableFuture
                .supplyAsync(() -> {
                    long startTime = System.nanoTime();
                    try {
                        MjpsPathfinder mjpsPathfinder = new MjpsPathfinder(startPos, endPos, terrainData);
                        List<InterpolationSegment> macroPath = mjpsPathfinder.findPath();
                        if (macroPath == null || macroPath.isEmpty()) {
                            throw new RuntimeException("Mjps失败，未找到路径。");
                        }

                        List<BlockPos> denseMicroPath = MicroPathfinder.interpolatePath(macroPath, terrainData);
                        if (denseMicroPath == null || denseMicroPath.isEmpty()) {
                            throw new RuntimeException("MicroPathfinder: 路径为空。");
                        }

                        long endTime = System.nanoTime();
                        CeaselessCaG.LOGGER.info("[DebugFullPath] 寻路耗时 {}ms。",
                                TimeUnit.NANOSECONDS.toMillis(endTime - startTime));

                        return new CalculationResult(macroPath, denseMicroPath);

                    } catch (Exception e) {
                        CeaselessCaG.LOGGER.error("[DebugFullPath] 计算失败:", e);
                        throw new CompletionException(e);
                    }
                }, ArchitectManager.INSTANCE.getPathCalculationExecutor())

                .thenApplyAsync((calcResult) -> {
                    CeaselessCaG.LOGGER.info("[DebugFullPath] 执行物理规划");
                    try {
                        ConstructionPlan plan = PreConstructionPlanner.createPlan(
                                level,
                                terrainData,
                                calcResult.macroPath(),
                                calcResult.denseMicroPath()
                        );

                        if (plan == null || plan.finalBlueprint().isEmpty()) {
                            throw new RuntimeException("PreConstructionPlanner失败，蓝图为空。");
                        }

                        return new VisualizationPackage(plan, calcResult.denseMicroPath());

                    } catch (Exception e) {
                        CeaselessCaG.LOGGER.error("[DebugFullPath]物理规划失败:", e);
                        throw new CompletionException(e);
                    }
                }, source.getServer())

                .whenCompleteAsync((vizPackage, error) -> {
                    if (error != null) {
                        source.sendFailure(Component.literal("计算失败: " + error.getMessage()));
                    } else if (vizPackage == null) {
                        source.sendFailure(Component.literal("计算失败：未返回可视化数据。"));
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                String.format("找到 %d 个宏观拐点, %d 个微观填充点, %d 个施工分段。正在可视化",
                                        vizPackage.plan().finalBlueprint().size(),
                                        vizPackage.denseMicroPath().size(),
                                        vizPackage.plan().segments().size()
                                )
                        ), true);

                        visualizeFullPath(source, vizPackage.plan(), vizPackage.denseMicroPath());
                    }
                }, source.getServer());

        return 1;
    }


    /**
     * 可视化
     * @param source           命令源
     * @param plan             施工计划
     */
    private static void visualizeFullPath(CommandSourceStack source, ConstructionPlan plan, List<BlockPos> denseMicroPath) {
        ServerLevel level = source.getLevel();

        Set<BlockPos> macroPoints = new HashSet<>(plan.finalBlueprint());
        BlockState macroBlock = Blocks.DIAMOND_BLOCK.defaultBlockState();
        for (BlockPos pos : macroPoints) {
            for (int i = 0; i < 5; i++) {
                BlockPos pillarPos = pos.above(i);
                if (level.isInWorldBounds(pillarPos)) {
                    level.setBlock(pillarPos, macroBlock, 2);
                }
            }
        }

        for (ConstructionPlan.ConstructionSegment segment : plan.segments()) {

            BlockPos correctedStart = segment.start();
            BlockPos correctedEnd = segment.end();
            BlockState currentBlock = getBlockForType(segment.type());

            for (BlockPos posOnLine : bresenham3D(correctedStart, correctedEnd)) {

                if (!macroPoints.contains(posOnLine)) {
                    if (level.isInWorldBounds(posOnLine)) {
                        level.setBlock(posOnLine, currentBlock, 2);
                    }
                }
            }
        }

        source.sendSuccess(() -> Component.literal("可视化完毕"), true);
    }

    /**
     * 用于可视化的方块
     */
    private static BlockState getBlockForType(ConstructionPlan.ConstructionType type) {
        return switch (type) {
            case GROUND -> Blocks.GOLD_BLOCK.defaultBlockState(); // 金块
            case NARROW_BRIDGE -> Blocks.LAPIS_BLOCK.defaultBlockState(); // 青金石块
            case VIADUCT -> Blocks.IRON_BLOCK.defaultBlockState(); // 铁块
            case TUNNEL -> Blocks.REDSTONE_BLOCK.defaultBlockState(); // 红石块
            case WIDE_WATER_ROUTE -> Blocks.PRISMARINE.defaultBlockState();
        };
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