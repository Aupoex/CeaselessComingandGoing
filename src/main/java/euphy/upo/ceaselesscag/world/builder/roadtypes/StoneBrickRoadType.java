package euphy.upo.ceaselesscag.world.builder.roadtypes;

import euphy.upo.ceaselesscag.world.builder.BuildContext;
import euphy.upo.ceaselesscag.world.builder.IBuilderComponent;
import euphy.upo.ceaselesscag.world.builder.IRoadType;
import euphy.upo.ceaselesscag.world.builder.components.foundation.DefaultClearanceComponent;
import euphy.upo.ceaselesscag.world.builder.components.lighting.SimpleLanternPostComponent;
import euphy.upo.ceaselesscag.world.builder.components.support.SmartSupportComponent;
import euphy.upo.ceaselesscag.world.builder.components.surface.StoneBrickSurfaceComponent;
import net.minecraft.resources.ResourceLocation;
import java.util.EnumMap;
import java.util.function.Predicate;

/**
 * 石砖路类型
 */
public class StoneBrickRoadType implements IRoadType {

    private final ResourceLocation id;

    private final EnumMap<IBuilderComponent.ComponentType, IBuilderComponent> components;

    public StoneBrickRoadType(ResourceLocation id) {
        this.id = id;
        this.components = new EnumMap<>(IBuilderComponent.ComponentType.class);


        this.components.put(
                IBuilderComponent.ComponentType.FOUNDATION,
                new DefaultClearanceComponent()
        );

        this.components.put(
                IBuilderComponent.ComponentType.SUPPORT,
                new SmartSupportComponent()
        );

        this.components.put(
                IBuilderComponent.ComponentType.SURFACE,
                new StoneBrickSurfaceComponent()
        );

        this.components.put(
                IBuilderComponent.ComponentType.LIGHTING,
                new SimpleLanternPostComponent()
        );

    }

    @Override
    public ResourceLocation getId() {
        return this.id;
    }

    @Override
    public EnumMap<IBuilderComponent.ComponentType, IBuilderComponent> getComponents() {
        return this.components;
    }

    @Override
    public Predicate<BuildContext> getSpawnCondition() {
        return (context) -> true;
    }

    @Override
    public int getPriority() {
        return 0;
    }
}