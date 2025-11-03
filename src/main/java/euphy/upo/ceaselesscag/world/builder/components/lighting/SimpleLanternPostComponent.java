package euphy.upo.ceaselesscag.world.builder.components.lighting;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IBuilderComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 简易路灯组件
 */
public class SimpleLanternPostComponent implements IBuilderComponent {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "simple_lantern_post");

    /**
     * 路灯的间隔
     */
    private static final int LAMP_SPACING = 16;

    /**
     * 路灯距离道路中心线多远
     */
    private static final int LAMP_OFFSET = 1;

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public ComponentType getType() {
        return ComponentType.LIGHTING;
    }

    @Override
    public boolean shouldPlace(BuildContext context) {
        BlockPos pos = context.getCurrentPos();
        return (Math.floorMod(pos.getX() + pos.getZ(), LAMP_SPACING) == 0);
    }

    @Override
    public void place(BuildContext context, BlockPos segmentStart, BlockPos segmentEnd) {

        BlockPos pos = context.getCurrentPos();
        int chunkCheck = Math.floorMod(Math.floorDiv(pos.getX(), 16) + Math.floorDiv(pos.getZ(), 16), 2);
        int offset = (chunkCheck == 0) ? -LAMP_OFFSET : LAMP_OFFSET;

        Direction facing = context.getApproximateDirection(segmentStart, segmentEnd);
        Direction crossDirection = facing.getCounterClockWise();

        BlockPos surfaceRelativePos = BlockPos.ZERO.relative(crossDirection, offset);
        BlockPos fenceRelativePos = surfaceRelativePos.above();

        BlockState surfaceState = context.getBlockState(surfaceRelativePos);
        if (surfaceState == null) {
            return;
        }

        BlockPos absoluteSurfacePos = context.getCurrentPos().offset(surfaceRelativePos);

        if (surfaceState.isFaceSturdy(context.getLevel(), absoluteSurfacePos, Direction.UP)) {

            context.setBlock(fenceRelativePos, Blocks.OAK_FENCE.defaultBlockState());

            context.setBlock(fenceRelativePos.above(), Blocks.LANTERN.defaultBlockState());
        }
    }
}