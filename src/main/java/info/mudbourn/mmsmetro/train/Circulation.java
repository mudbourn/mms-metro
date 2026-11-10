package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Seeds a line's trains in one pass, spread evenly over the stops of both directions in round-trip order.
final class Circulation {

    // Stops per train when no train count is given.
    static final int SPACING_STOPS = 2;

    // One terminal's share of the seeding: the path it runs on and the lead arcs its trains start at.
    record Launcher(RailPath path, List<Double> arcs) {
    }

    private Circulation() {
    }

    // A terminal's seed arcs: the terminal and its stops short of the far terminus.
    static List<Double> seedArcs(double headArc, List<PathStation> stops) {
        List<Double> positions = new ArrayList<>();
        positions.add(headArc);
        for (PathStation stop : stops) {
            if (!stop.terminus()) {
                positions.add(stop.arc());
            }
        }
        return positions;
    }

    // Seed spots across every launcher in round-trip order.
    static int spots(List<Launcher> launchers) {
        int count = 0;
        for (Launcher launcher : launchers) {
            count += launcher.arcs().size();
        }
        return count;
    }

    // Spawns the requested number of trains, capped at one per spot, at evenly spaced free spots, and returns how many it placed.
    static int seed(MetroState state, List<Launcher> launchers, int trains, int cars, MetroConfig config) {
        int total = spots(launchers);
        int wanted = Math.min(trains, total);
        int spawned = 0;
        for (int n = 0; n < wanted; n++) {
            int index = (int) ((long) n * total / wanted);
            for (Launcher launcher : launchers) {
                if (index >= launcher.arcs().size()) {
                    index -= launcher.arcs().size();
                    continue;
                }
                double arc = launcher.arcs().get(index);
                if (!ConsistManager.arcOccupied(state, launcher.path(), arc, cars, config)) {
                    Consist consist = ConsistManager.createTrain(state, launcher.path(), arc, cars, config);
                    consist.scheduleRedraw(ConsistManager.REDRAW_DELAY);
                    spawned++;
                }
                break;
            }
        }
        return spawned;
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
