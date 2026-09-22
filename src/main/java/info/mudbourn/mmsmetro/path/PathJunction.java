package info.mudbourn.mmsmetro.path;

import net.minecraft.util.math.BlockPos;

// A junction rail node resolved onto the path: its arc position and the rail block it sits on.
public record PathJunction(
    double arc,
    BlockPos rail) {
}
