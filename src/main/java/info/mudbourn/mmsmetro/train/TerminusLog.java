package info.mudbourn.mmsmetro.train;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Departures from one terminus: the latest, each train's latest, and the smoothed round-trip time between a train's departures.
final class TerminusLog {

    // Weight of the newest round trip in the smoothed cycle.
    private static final double SMOOTHING = 0.3;

    private final Map<UUID, Long> byTrain = new HashMap<>();

    private long last = Long.MIN_VALUE;

    private double cycle;

    // Ticks a departure must still wait to sit an even share of the cycle after the last one, or 0 when it may leave.
    long waitTicks(long now, int trains) {
        if (this.cycle <= 0.0 || trains <= 1 || this.last == Long.MIN_VALUE) {
            return 0L;
        }
        return Math.max(0L, this.last + Math.round(this.cycle / trains) - now);
    }

    // Records a departure and folds the train's round trip into the smoothed cycle.
    void depart(UUID train, long now) {
        Long previous = this.byTrain.put(train, now);
        if (previous != null) {
            double lap = now - previous;
            this.cycle = this.cycle <= 0.0 ? lap : this.cycle + SMOOTHING * (lap - this.cycle);
        }
        this.last = now;
    }
}
