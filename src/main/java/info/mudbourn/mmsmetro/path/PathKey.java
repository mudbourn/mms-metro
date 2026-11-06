package info.mudbourn.mmsmetro.path;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// The rail and heading a path is walked from, which identifies one baked path.
public record PathKey(BlockPos origin, Direction dir) {

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("Origin", this.origin.asLong());
        nbt.putInt("Dir", this.dir.getIndex());
        return nbt;
    }

    public static PathKey fromNbt(NbtCompound nbt) {
        return new PathKey(
            BlockPos.fromLong(nbt.getLong("Origin", 0L)),
            Direction.byIndex(nbt.getInt("Dir", Direction.NORTH.getIndex()))
        );
    }
}
