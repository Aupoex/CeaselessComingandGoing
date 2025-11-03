package euphy.upo.ceaselesscag.events;

import com.mojang.brigadier.CommandDispatcher;
import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.command.DebugFullPathCommand;
import euphy.upo.ceaselesscag.world.architect.ArchitectManager;
import euphy.upo.ceaselesscag.world.dynamic.DynamicRoadManager;
import euphy.upo.ceaselesscag.world.dynamic.RetroactiveBuilderManager;
import euphy.upo.ceaselesscag.world.terrain.TerrainDataManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;

/**
 * 核心游戏生命周期事件监听器 。
 * 1. 监听服务器启动/停止/Tick 事件。
 * 2. 作为所有管理器(Managers)的总开关。
 */
public class CoreLifecycleEvents {

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {

        // 初始化地形数据库
        NoiseSettings noiseSettings = getOverworldNoiseSettings(event.getServer());
        if (noiseSettings == null) {
            CeaselessCaG.LOGGER.error("错误: 无法获取主世界 NoiseSettings ");
            CeaselessCaG.LOGGER.error("CeaselessCaG JIT 系统无法启动");
            return;
        }
        TerrainDataManager.getInstance().initialize(noiseSettings);

        // 启动 JIT 引擎
        ArchitectManager.INSTANCE.start();
        DynamicRoadManager.INSTANCE.start();
        RetroactiveBuilderManager.INSTANCE.start();
    }


    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {

        DynamicRoadManager.INSTANCE.onTick(event);
        RetroactiveBuilderManager.INSTANCE.onTick(event);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {

        RetroactiveBuilderManager.INSTANCE.shutdown();
        DynamicRoadManager.INSTANCE.shutdown();
        ArchitectManager.INSTANCE.shutdown();
        TerrainDataManager.getInstance().shutdown();
    }

    /**
     * 安全地获取主世界的 NoiseSettings。
     */
    @Nullable
    private NoiseSettings getOverworldNoiseSettings(MinecraftServer server) {
        try {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                CeaselessCaG.LOGGER.error("[CoreEvents] 无法在 onServerStarting 期间获取主世界 ");
                return null;
            }

            ChunkGenerator chunkGenerator = overworld.getChunkSource().getGenerator();
            if (chunkGenerator instanceof NoiseBasedChunkGenerator noiseGen) {
                return noiseGen.generatorSettings().value().noiseSettings();
            } else {
                CeaselessCaG.LOGGER.error("[CoreEvents] 主世界区块生成器不是 NoiseBasedChunkGenerator(类型: {})", chunkGenerator.getClass().getName());
                return null;
            }
        } catch (Exception e) {
            CeaselessCaG.LOGGER.error("[CoreEvents] 获取 NoiseSettings 时发生意外错误:", e);
            return null;
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        DebugFullPathCommand.register(dispatcher);
    }
}