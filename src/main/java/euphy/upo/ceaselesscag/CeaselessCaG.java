package euphy.upo.ceaselesscag;

import com.mojang.logging.LogUtils;
import euphy.upo.ceaselesscag.config.Config;
import euphy.upo.ceaselesscag.events.CoreLifecycleEvents;
import euphy.upo.ceaselesscag.poi.PoiRegistry;
import euphy.upo.ceaselesscag.registry.ItemRegistry;
import euphy.upo.ceaselesscag.world.builder.RoadBuilderRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(CeaselessCaG.MODID)
public class CeaselessCaG {

    public static final String MODID = "ceaselesscag";
    public static final Logger LOGGER = LogUtils.getLogger();
    public CeaselessCaG(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(Config::onReload);
        NeoForge.EVENT_BUS.register(new CoreLifecycleEvents());
        ItemRegistry.ITEMS.register(modEventBus);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            PoiRegistry.registerDefaults();
            RoadBuilderRegistry.registerDefaults();
        });
    }
}