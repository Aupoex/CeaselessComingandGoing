package euphy.upo.ceaselesscag.world.builder.components.support;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IBuilderComponent;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;
import euphy.upo.ceaselesscag.world.builder.components.decoration.BuoyComponent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 统一的智能支撑组件
 * 决策依赖于 `BuildContext`提供的、由 `PreConstructionPlanner`确定的施工指令。
 * 它会根据 `ConstructionType`切换行为。
 */
public class SmartSupportComponent implements IBuilderComponent {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "smart_support");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public ComponentType getType() {
        return ComponentType.SUPPORT;
    }

    @Override
    public boolean shouldPlace(BuildContext context) {
        /*
        ConstructionPlan.ConstructionType type = context.getConstructionType();
        return type == ConstructionPlan.ConstructionType.NARROW_BRIDGE
                || type == ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE
                || type == ConstructionPlan.ConstructionType.VIADUCT
                || type == ConstructionPlan.ConstructionType.TUNNEL;
         */
        return true;
    }

    @Override
    public void place(BuildContext context, BlockPos segmentStart, BlockPos segmentEnd) {
        ConstructionPlan.ConstructionType type = context.getConstructionType();

        switch (type) {
            case GROUND:
            case NARROW_BRIDGE:

                placeBridgeSupport(context);
                break;

            case VIADUCT:

                placeBridgeSupport(context);
                break;

            case WIDE_WATER_ROUTE:

                BuoyComponent buoy = new BuoyComponent();
                if (buoy.shouldPlace(context)) {
                    buoy.place(context, segmentStart, segmentEnd);
                }
                break;

            case TUNNEL:

                placeTunnel(context);
                break;

            default:
                break;
        }
    }

    private void placeBridgeSupport(BuildContext context) {
        BlockPos currentPos = context.getCurrentPos();
        int bridgeDeckY = currentPos.getY() - 1;

        if ((currentPos.getX() + currentPos.getZ()) % 7 != 0) {
            return;
        }
        BlockState pillarState = Blocks.STONE_BRICKS.defaultBlockState();
        BlockPos.MutableBlockPos pillarPos = new BlockPos.MutableBlockPos(
                0,
                -1,
                0
        );
        for (int i = 0; i < 64; i++) {
            BlockState existingState = context.getBlockState(pillarPos);
            if (existingState == null) {
                break;
            }
            if (!existingState.isAir() && existingState.getFluidState().isEmpty()) {

                break;
            }
            context.setBlock(pillarPos, pillarState);
            pillarPos.move(Direction.DOWN);
        }
    }

    private void placeTunnel(BuildContext context) {
        BlockState wallState = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState airState = Blocks.CAVE_AIR.defaultBlockState();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos relativePos = new BlockPos(dx, dy, 0);

                BlockState stateToPlace = (dx == -1 || dx == 1 || dy == 2) ? wallState : airState;

                BlockState existingState = context.getBlockState(relativePos);

                if (existingState != null && !existingState.isAir()) {
                    context.setBlock(relativePos, stateToPlace);
                }
            }
        }
    }
}