package euphy.upo.ceaselesscag.world.builder.roadtypes;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.world.builder.IRoadType;
import net.minecraft.resources.ResourceLocation;

/**
 * 默认道路类型，负责实例化
 */
public final class DefaultRoadTypes {

    private DefaultRoadTypes() {}

    public static final IRoadType STONE_BRICK_ROAD = new StoneBrickRoadType(
            ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "stone_brick_road")
    );
    public static final IRoadType DEEPSLATE_ROAD = new DeepslateRoadType(
            ResourceLocation.fromNamespaceAndPath(CeaselessCaG.MODID, "deepslate_road")
    );

}