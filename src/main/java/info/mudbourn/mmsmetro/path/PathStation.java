package info.mudbourn.mmsmetro.path;

import net.minecraft.util.math.BlockPos;

// A station stop resolved onto the path: where along the track a train halts,
// how long it dwells, the marker block it belongs to (for speaker lookup), and
// the station's editable identity used by the onboard HUD and announcements.
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
    String transferLine) {
}
