package euphy.upo.ceaselesscag.world.pathfinding;

import net.minecraft.core.BlockPos;

/**
 * 插值分段
 */
public record InterpolationSegment(

        BlockPos start,


        BlockPos end,

        InterpolationType type
) {}