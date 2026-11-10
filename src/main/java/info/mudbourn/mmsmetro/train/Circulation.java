package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

// One running circulate: each terminal launches its trains one wave at a time, the next leaving once the last is SPACING_STOPS stops out.
final class Circulation {

    // Stops a train must get away from its terminal before the next one leaves, which also sets one train per this many stops.
    static final int SPACING_STOPS = 2;

    // One terminal's share of the circulation: the path it launches on, where trains start, the arc that releases the next train, the terminal and its neighbouring stop, and the train it is waiting on.
    static final class Launcher {

        private final RailPath path;

        private final double headArc;

        private final double triggerArc;

        private final String terminalKey;

        private final String neighbourKey;

        private boolean holding;

        private int remaining;

        private UUID waiting;

        private int waitingNumber;

        Launcher(RailPath path, double headArc, double triggerArc, String terminal, String neighbour, int trains) {
            this.path = path;
            this.headArc = headArc;
            this.triggerArc = triggerArc;
            this.terminalKey = key(terminal);
            this.neighbourKey = key(neighbour);
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

    // True once every train has been placed and has got SPACING_STOPS stops out or left the circulation.
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
                if (onPath && train.headArc() + 1.0e-3 < launcher.triggerArc) {
                    continue;
                }
                launcher.waiting = null;
                this.done++;
                if (train != null) {
                    tell("Train " + launcher.waitingNumber + " is " + SPACING_STOPS + " stops out, continuing.");
                }
                report();
            }
            if (launcher.remaining > 0) {
                launch(state, launcher, config);
            }
        }
    }

    // Places the next train at a terminal unless another train still stands there or is coming in from the neighbouring stop.
    private void launch(MetroState state, Launcher launcher, MetroConfig config) {
        if (inbound(state, launcher)) {
            if (!launcher.holding) {
                launcher.holding = true;
                tell("Holding " + this.lineName + ": a train is coming in to its terminal, waiting for it to clear.");
            }
            return;
        }
        launcher.holding = false;
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

    // True when a train is running to the terminal, or calling at its neighbouring stop with the terminal next.
    private static boolean inbound(MetroState state, Launcher launcher) {
        for (Consist train : state.trains()) {
            String[] next = train.nextTwoStops();
            String first = key(next[0]);
            if (train.isRunning() && first.equals(launcher.terminalKey)) {
                return true;
            }
            if (!train.isRunning() && first.equals(launcher.neighbourKey) && key(next[1]).equals(launcher.terminalKey)) {
                return true;
            }
        }
        return false;
    }

    private void report() {
        tell("Circulating " + this.lineName + ": " + this.done + "/" + this.total + " trains done. "
            + this.spawned + " trains spawned.");
    }

    private void tell(String message) {
        this.source.sendFeedback(() -> Text.literal(message), false);
    }

    // The distinct stations past a terminal, nearest first, up to and including the first terminus.
    static List<PathStation> stopsAhead(RailPath path, double fromArc, String terminalName) {
        List<PathStation> ahead = new ArrayList<>();
        for (PathStation station : path.stations()) {
            if (station.arc() > fromArc + 1.0e-3) {
                ahead.add(station);
            }
        }
        ahead.sort(Comparator.comparingDouble(PathStation::arc));
        Set<String> seen = new HashSet<>();
        seen.add(key(terminalName));
        List<PathStation> stops = new ArrayList<>();
        for (PathStation station : ahead) {
            if (seen.add(key(station.name()))) {
                stops.add(station);
                if (station.terminus()) {
                    break;
                }
            }
        }
        return stops;
    }

    // A station name lowercased with every character that is not a Roman letter or digit removed.
    private static String key(String name) {
        return ConsistManager.lineId(name).toLowerCase(Locale.ROOT);
    }
}
