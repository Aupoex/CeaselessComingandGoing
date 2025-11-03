package euphy.upo.ceaselesscag.item;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import euphy.upo.ceaselesscag.CeaselessCaG;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 在选择的两点间生成可视化调试路径
 */
public class PathfinderToolItem extends Item {


    @Nullable
    private static BlockPos firstPos = null;

    public PathfinderToolItem(Properties pProperties) {
        super(pProperties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }
        BlockPos clickedPos = context.getClickedPos();

        if (firstPos == null) {
            firstPos = clickedPos;
            player.sendSystemMessage(Component.literal("§a[调试工具] 已设置第一个点 (XZ): " + posToString(firstPos)));

        } else {
            BlockPos secondPos = clickedPos;

            if (firstPos.equals(secondPos)) {
                player.sendSystemMessage(Component.literal("§c[调试工具] 选择的点相同，清除选择。"));
                firstPos = null;
                return InteractionResult.SUCCESS;
            }

            player.sendSystemMessage(Component.literal("§a[调试工具] 已设置第二个点 (XZ): " + posToString(secondPos)));

            player.sendSystemMessage(Component.literal("§e正在执行调试路径生成..."));

            String command = String.format("ccg_debug_full_path %d %d %d %d",
                    firstPos.getX(), firstPos.getZ(),
                    secondPos.getX(), secondPos.getZ()
            );

            CommandSourceStack source = player.createCommandSourceStack();

            Commands commands = player.getServer().getCommands();
            CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();

            try {
                ParseResults<CommandSourceStack> parseResults = dispatcher.parse(command, source);

                commands.performCommand(parseResults, command);

            } catch (Exception e) {
                source.sendFailure(Component.literal("§c执行命令失败: " + e.getMessage()));
                CeaselessCaG.LOGGER.error("[PathfinderToolItem] 无法执行调试命令: /{}", command, e);
            }

            firstPos = null;
        }

        return InteractionResult.SUCCESS;
    }

    private String posToString(BlockPos pos) {
        return String.format("%d, %d", pos.getX(), pos.getZ());
    }

    public static void clearSelection() {
        firstPos = null;
    }
}