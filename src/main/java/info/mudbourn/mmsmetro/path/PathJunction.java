package info.mudbourn.mmsmetro.path;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

// A junction rail node resolved onto the path, with the nearest station block that decides priority there (null when none).
public record PathJunction(
    double arc,
    BlockPos rail,
    BlockPos station) {

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putDouble("Arc", this.arc);
        nbt.putLong("Rail", this.rail.asLong());
        if (this.station != null) {
            nbt.putLong("Station", this.station.asLong());
        }
        return nbt;
    }

    public static PathJunction fromNbt(NbtCompound nbt) {
        return new PathJunction(
            nbt.getDouble("Arc", 0.0),
            BlockPos.fromLong(nbt.getLong("Rail", 0L)),
            nbt.getLong("Station").map(BlockPos::fromLong).orElse(null)
        );
    }
}
