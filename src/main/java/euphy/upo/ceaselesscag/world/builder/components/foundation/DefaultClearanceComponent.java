package euphy.upo.ceaselesscag.world.builder.components.foundation;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IBuilderComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 默认清理组件
 * 清除道路方块上方数格的空间。
 */
public class DefaultClearanceComponent implements IBuilderComponent {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "default_clearance");

    private static final int CLEARANCE_HEIGHT_ABOVE_PATH = 4;
    private static final int WIDTH = 3;

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public ComponentType getType() {
        return ComponentType.FOUNDATION;
    }

    @Override
    public boolean shouldPlace(BuildContext context) {
        return true;
    }

    @Override
    public void place(BuildContext context, BlockPos segmentStart, BlockPos segmentEnd) {

        Direction facing = context.getApproximateDirection(segmentStart, segmentEnd);
        Direction crossDirection = facing.getCounterClockWise();
        int halfWidth = WIDTH / 2;

        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            BlockPos relativePosBase = BlockPos.ZERO.relative(crossDirection, offset);

            for (int i = 0; i < CLEARANCE_HEIGHT_ABOVE_PATH; i++) {
                BlockPos clearancePos = relativePosBase.above(i);

                BlockState existingState = context.getBlockState(clearancePos);
                if (existingState != null && !existingState.isAir()) {
                    context.setBlock(clearancePos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}