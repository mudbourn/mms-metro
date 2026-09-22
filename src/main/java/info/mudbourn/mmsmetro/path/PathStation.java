package info.mudbourn.mmsmetro.path;

import net.minecraft.util.math.BlockPos;

// A station stop resolved onto the path: where a train halts, how long it dwells, its marker block (for speaker lookup), and its editable identity for the HUD and announcements; a set direction is the label the train shows leaving this stop, else it shows its compass heading of travel.
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
    String lineColor) {
}
