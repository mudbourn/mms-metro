package info.mudbourn.mmsmetro.path;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

// A trackside announcement trigger resolved onto the path, carrying the upcoming station's details and its speaker position (null when none).
public record PathBump(
    double arc,
    BlockPos stationPos,
    String stationName,
    String exitDirection,
    boolean hub,
    String transferLine,
    boolean terminal,
    BlockPos speaker) {

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putDouble("Arc", this.arc);
        nbt.putLong("Station", this.stationPos.asLong());
        nbt.putString("Name", this.stationName);
        nbt.putString("Exit", this.exitDirection);
        nbt.putBoolean("Hub", this.hub);
        nbt.putString("Transfer", this.transferLine);
        nbt.putBoolean("Terminal", this.terminal);
        if (this.speaker != null) {
            nbt.putLong("Speaker", this.speaker.asLong());
        }
        return nbt;
    }

    public static PathBump fromNbt(NbtCompound nbt) {
        return new PathBump(
            nbt.getDouble("Arc", 0.0),
            BlockPos.fromLong(nbt.getLong("Station", 0L)),
            nbt.getString("Name", ""),
            nbt.getString("Exit", ""),
            nbt.getBoolean("Hub", false),
            nbt.getString("Transfer", ""),
            nbt.getBoolean("Terminal", false),
            nbt.getLong("Speaker").map(BlockPos::fromLong).orElse(null)
        );
    }
}
