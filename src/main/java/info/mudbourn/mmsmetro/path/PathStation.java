package info.mudbourn.mmsmetro.path;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

// A station stop resolved onto the path with its editable identity and its speaker position (null when none).
public record PathStation(
    double arc,
    int dwellTicks,
    BlockPos pos,
    boolean terminus,
    String name,
    String line,
    String direction,
    String nextStation,
    String exitDirection,
    boolean hub,
    String transferLine,
    String lineColor,
    BlockPos speaker) {

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putDouble("Arc", this.arc);
        nbt.putInt("Dwell", this.dwellTicks);
        nbt.putLong("Pos", this.pos.asLong());
        nbt.putBoolean("Terminus", this.terminus);
        nbt.putString("Name", this.name);
        nbt.putString("Line", this.line);
        nbt.putString("Direction", this.direction);
        nbt.putString("Next", this.nextStation);
        nbt.putString("Exit", this.exitDirection);
        nbt.putBoolean("Hub", this.hub);
        nbt.putString("Transfer", this.transferLine);
        nbt.putString("Color", this.lineColor);
        if (this.speaker != null) {
            nbt.putLong("Speaker", this.speaker.asLong());
        }
        return nbt;
    }

    public static PathStation fromNbt(NbtCompound nbt) {
        return new PathStation(
            nbt.getDouble("Arc", 0.0),
            nbt.getInt("Dwell", 0),
            BlockPos.fromLong(nbt.getLong("Pos", 0L)),
            nbt.getBoolean("Terminus", false),
            nbt.getString("Name", ""),
            nbt.getString("Line", ""),
            nbt.getString("Direction", ""),
            nbt.getString("Next", ""),
            nbt.getString("Exit", ""),
            nbt.getBoolean("Hub", false),
            nbt.getString("Transfer", ""),
            nbt.getString("Color", "white"),
            nbt.getLong("Speaker").map(BlockPos::fromLong).orElse(null)
        );
    }
}
