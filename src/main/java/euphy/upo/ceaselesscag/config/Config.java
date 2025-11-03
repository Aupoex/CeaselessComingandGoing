package euphy.upo.ceaselesscag.config;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.poi.PoiRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.List;

public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_POIS = BUILDER
            .comment(
                    "A list of custom Points of Interest (POIs) to be connected by roads.",
                    "Entries must be valid Structure IDs (e.g., 'minecraft:pillager_outpost')."
            )
            .defineListAllowEmpty(
                    "customPois",
                    Collections.emptyList(),
                    Config::validateStructureName
            );

    public static final ModConfigSpec SPEC = BUILDER.build();


    private static boolean validateStructureName(final Object obj) {
        if (obj instanceof String structureName) {
            try {
                ResourceLocation rl = ResourceLocation.parse(structureName);

                return BuiltInRegistries.STRUCTURE_TYPE.containsKey(rl);
            } catch (Exception e) {
                CeaselessCaG.LOGGER.warn("[ModConfig] 配置文件验证失败：'{}' 不是一个有效的 ResourceLocation。", structureName);
                return false;
            }
        }
        return false;
    }


    public static void onLoad(final ModConfigEvent.Loading event) {
        PoiRegistry.clearCustomPois();

        int count = 0;

        for (String structureName : CUSTOM_POIS.get()) {
            try {
                ResourceLocation rl = ResourceLocation.parse(structureName);
                ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, rl);

                PoiRegistry.registerCustom(key);
                count++;
            } catch (Exception e) {
                CeaselessCaG.LOGGER.error("[ModConfig] 无法加载自定义 POI '{}': {}", structureName, e.getMessage());
            }
        }
        CeaselessCaG.LOGGER.info("[ModConfig] 加载了 {} 个自定义 POI。", count);
    }


    public static void onReload(final ModConfigEvent.Reloading event) {
        onLoad(null);
    }
}