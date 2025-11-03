package euphy.upo.ceaselesscag.poi;

import com.google.common.collect.ImmutableSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * POI 注册表。
 * 提供 API `public static void register(ResourceKey<Structure> structureKey)`。
 * 允许其他模组添加新的、可被道路连接的 POI。
 */
public final class PoiRegistry {

    private PoiRegistry() {}

    private static final Set<ResourceKey<Structure>> REGISTERED_DEFAULTS = ConcurrentHashMap.newKeySet();

    private static final Set<ResourceKey<Structure>> REGISTERED_CUSTOM = ConcurrentHashMap.newKeySet();


    public static Set<ResourceKey<Structure>> getRegisteredPois() {
        return ImmutableSet.<ResourceKey<Structure>>builder()
                .addAll(REGISTERED_DEFAULTS)
                .addAll(REGISTERED_CUSTOM)
                .build();
    }

    private static void registerDefault(ResourceKey<Structure> structureKey) {
        if (structureKey != null) {
            REGISTERED_DEFAULTS.add(structureKey);
        }
    }

    public static void registerCustom(ResourceKey<Structure> structureKey) {
        if (structureKey != null) {
            REGISTERED_CUSTOM.add(structureKey);
        }
    }

    public static void clearCustomPois() {
        REGISTERED_CUSTOM.clear();
    }


    public static void registerDefaults() {

        registerDefault(BuiltinStructures.VILLAGE_PLAINS);
        registerDefault(BuiltinStructures.VILLAGE_DESERT);
        registerDefault(BuiltinStructures.VILLAGE_SAVANNA);
        registerDefault(BuiltinStructures.VILLAGE_SNOWY);
        registerDefault(BuiltinStructures.VILLAGE_TAIGA);

        //register(BuiltinStructures.WOODLAND_MANSION);
        //register(BuiltinStructures.DESERT_PYRAMID);
    }
}