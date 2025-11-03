package euphy.upo.ceaselesscag.world.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

/**
 * 宏观路径分段
 */
public record MacroPathSegment(BlockPos start, BlockPos end, PathIntent intent) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("start", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, start).getOrThrow());
        tag.put("end", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, end).getOrThrow());
        tag.putString("intent", intent.name());
        return tag;
    }

    public static MacroPathSegment load(CompoundTag tag) {
        BlockPos start = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("start")).getOrThrow();
        BlockPos end = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("end")).getOrThrow();
        PathIntent intent = PathIntent.valueOf(tag.getString("intent"));
        return new MacroPathSegment(start, end, intent);
    }
}