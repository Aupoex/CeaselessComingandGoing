package euphy.upo.ceaselesscag.world.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

import java.util.Objects;

/**
 * 道路分段 (Road Segment)。
 * 代表一条连接两个世界坐标点 (POI) 的道路。
 * 负责存储一条规划好的道路的起点和终点。
 */
public record RoadSegment(BlockPos pos1, BlockPos pos2) {

    /**
     * 实现无向性。
     * 两个分段包含相同的两个点，它们会被视为相等。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadSegment that = (RoadSegment) o;
        return (Objects.equals(pos1, that.pos1) && Objects.equals(pos2, that.pos2)) ||
                (Objects.equals(pos1, that.pos2) && Objects.equals(pos2, that.pos1));
    }

    /**
     * 实现无向性。
     * @return 一个与顺序无关的哈希值。
     */
    @Override
    public int hashCode() {
        return Objects.hash(pos1) + Objects.hash(pos2);
    }

    /**
     * 将此分段保存。
     * @return 包含此分段数据的 CompoundTag
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("p1", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, pos1).getOrThrow());
        tag.put("p2", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, pos2).getOrThrow());
        return tag;
    }

    /**
     * 加载一个分段。
     * @param tag 包含分段数据的 CompoundTag
     * @return 一个新的 RoadSegment 实例
     */
    public static RoadSegment load(CompoundTag tag) {
        BlockPos p1 = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("p1")).getOrThrow();
        BlockPos p2 = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("p2")).getOrThrow();
        return new RoadSegment(p1, p2);
    }
}