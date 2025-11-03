package euphy.upo.ceaselesscag.world.builder.components.surface;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IBuilderComponent;
import euphy.upo.ceaselesscag.world.builder.planning.ConstructionPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 深板岩路面组件
 */
public class DeepslateSurfaceComponent implements IBuilderComponent {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "deepslate_surface_3w");
    private static final int WIDTH = 3;


    private static final BlockState CENTER_BLOCK = Blocks.DEEPSLATE_BRICKS.defaultBlockState();

    private static final List<BlockState> VARIED_BLOCKS = List.of(
            Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState(),
            Blocks.TUFF.defaultBlockState()
    );
    private static final float VARIATION_CHANCE = 0.15f;

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public ComponentType getType() {
        return ComponentType.SURFACE;
    }

    @Override
    public boolean shouldPlace(BuildContext context) {
        return true;
    }


    @Override
    public void place(BuildContext context, BlockPos segmentStart, BlockPos segmentEnd) {
        ConstructionPlan.ConstructionType type = context.getConstructionType();

        if (type == ConstructionPlan.ConstructionType.WIDE_WATER_ROUTE) {
            return;

        }

        Direction facing = context.getApproximateDirection(segmentStart, segmentEnd);
        Direction crossDirection = facing.getCounterClockWise();
        int halfWidth = WIDTH / 2;

        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            BlockPos relativePosBase = BlockPos.ZERO.relative(crossDirection, offset);
            BlockState blockToPlace;

            if (type == ConstructionPlan.ConstructionType.NARROW_BRIDGE) {

                blockToPlace = Blocks.OAK_PLANKS.defaultBlockState();

            } else {

                if (context.getRandom() != null && context.getRandom().nextFloat() < VARIATION_CHANCE) {
                    blockToPlace = VARIED_BLOCKS.get(context.getRandom().nextInt(VARIED_BLOCKS.size()));
                } else {
                    blockToPlace = CENTER_BLOCK;
                }
            }


            BlockPos targetPos = relativePosBase;

            context.setBlock(targetPos, blockToPlace);

        }
    }
}