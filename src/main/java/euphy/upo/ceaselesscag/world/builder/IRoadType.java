package euphy.upo.ceaselesscag.world.builder;

import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 道路类型接口
 */
public interface IRoadType {

    ResourceLocation getId();

    EnumMap<IBuilderComponent.ComponentType, IBuilderComponent> getComponents();

    default Optional<IBuilderComponent> getComponent(IBuilderComponent.ComponentType type) {
        return Optional.ofNullable(getComponents().get(type));
    }

    Predicate<BuildContext> getSpawnCondition();

    default int getPriority() {
        return 0;
    }
}