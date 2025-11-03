package euphy.upo.ceaselesscag.world.builder.components.decoration;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IBuilderComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/**
 * 海洋航标组件
 * 在宽阔的水域放置航标
 */
public class BuoyComponent implements IBuilderComponent {

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "buoy_decoration");

    private static final int BUOY_SPACING = 16;

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public ComponentType getType() {
        return ComponentType.DECORATION;
    }

    @Override
    public boolean shouldPlace(BuildContext context) {
        BlockPos pos = context.getCurrentPos();
        return (Math.floorMod(pos.getX(), BUOY_SPACING) + Math.floorMod(pos.getZ(), BUOY_SPACING)) % BUOY_SPACING == 0;
    }

    @Override
    public void place(BuildContext context, BlockPos segmentStart, BlockPos segmentEnd) {
        BlockPos pathPos = context.getCurrentPos();


        BlockPos plankPos = pathPos.below();
        BlockPos fencePos = pathPos;
        BlockPos lanternPos = pathPos.above();

        BlockPos relativeBase = BlockPos.ZERO;

        context.setBlock(relativeBase.below(), Blocks.AIR.defaultBlockState());
        context.setBlock(relativeBase, Blocks.AIR.defaultBlockState());

        context.setBlock(relativeBase.below(), Blocks.OAK_PLANKS.defaultBlockState());
        context.setBlock(relativeBase, Blocks.OAK_FENCE.defaultBlockState());
        context.setBlock(relativeBase.above(), Blocks.LANTERN.defaultBlockState());
    }
}