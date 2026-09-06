package info.mudbourn.mmsmetro.path;

import net.minecraft.util.math.BlockPos;

// A station stop resolved onto the path: where along the track a train halts,
// how long it dwells, and the marker block it belongs to (for speaker lookup).
public record PathStation(double arc, int dwellTicks, BlockPos pos) {
}
