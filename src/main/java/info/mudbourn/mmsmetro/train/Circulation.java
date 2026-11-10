package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

// One running circulate: each terminal launches its trains one wave at a time, the next leaving once the last reaches its first stop.
final class Circulation {

    // One terminal's share of the circulation: the path it launches on, where trains start, and the train it is waiting on.
    static final class Launcher {

        private final RailPath path;

        private final double headArc;

        private final double firstStopArc;

        private int remaining;

        private UUID waiting;

        private int waitingNumber;

        Launcher(RailPath path, double headArc, double firstStopArc, int trains) {
            this.path = path;
            this.headArc = headArc;
            this.firstStopArc = firstStopArc;
            this.remaining = trains;
        }
    }

    private final ServerWorld world;

    private final String lineName;

    private final ServerCommandSource source;

    private final List<Launcher> launchers;

    private final int cars;

    private final int total;

    private int spawned;

    private int done;

    Circulation(ServerWorld world, String lineName, ServerCommandSource source, List<Launcher> launchers, int cars) {
        this.world = world;
        this.lineName = lineName;
        this.source = source;
        this.launchers = launchers;
        this.cars = cars;
        int sum = 0;
        for (Launcher launcher : launchers) {
            sum += launcher.remaining;
        }
        this.total = sum;
    }

    ServerWorld world() {
        return this.world;
    }

    // Trains the circulation will place in all.
    int total() {
        return this.total;
    }

    // True once every train has been placed and has reached its first stop or left the circulation.
    boolean finished() {
        for (Launcher launcher : this.launchers) {
            if (launcher.remaining > 0 || launcher.waiting != null) {
                return false;
            }
        }
        return true;
    }

    // Checks each terminal's waiting train and launches the next wave from any terminal that is free.
    void tick(MetroState state, MetroConfig config) {
        for (Launcher launcher : this.launchers) {
            if (launcher.waiting != null) {
                Consist train = state.train(launcher.waiting);
                boolean onPath = train != null && train.path() == launcher.path;
                if (onPath && train.headArc() + 1.0e-3 < launcher.firstStopArc) {
                    continue;
                }
                launcher.waiting = null;
                this.done++;
                if (train != null) {
                    tell("Train " + launcher.waitingNumber + " has reached its first stop, continuing.");
                }
                report();
            }
            if (launcher.remaining > 0) {
                launch(state, launcher, config);
            }
        }
    }

    // Places the next train at a terminal unless another train still stands there.
    private void launch(MetroState state, Launcher launcher, MetroConfig config) {
        if (ConsistManager.arcOccupied(state, launcher.path, launcher.headArc, this.cars, config)) {
            return;
        }
        Consist consist = ConsistManager.createTrain(state, launcher.path, launcher.headArc, this.cars, config);
        consist.scheduleRedraw(ConsistManager.REDRAW_DELAY);
        launcher.remaining--;
        this.spawned++;
        launcher.waiting = consist.id();
        launcher.waitingNumber = this.spawned;
        report();
    }

    private void report() {
        tell("Circulating " + this.lineName + ": " + this.done + "/" + this.total + " trains done. "
            + this.spawned + " trains spawned.");
    }

    private void tell(String message) {
        this.source.sendFeedback(() -> Text.literal(message), false);
    }

    // The arc of the first stop past a terminal station whose name differs from the terminal's, or NaN when none lies ahead.
    static double firstStopArc(RailPath path, double fromArc, String terminalName) {
        double best = Double.NaN;
        for (PathStation station : path.stations()) {
            if (station.arc() > fromArc + 1.0e-3 && !station.name().equalsIgnoreCase(terminalName)
                && (Double.isNaN(best) || station.arc() < best)) {
                best = station.arc();
            }
        }
        return best;
    }
}
