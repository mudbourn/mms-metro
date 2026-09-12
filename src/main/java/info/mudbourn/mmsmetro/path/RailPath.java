package info.mudbourn.mmsmetro.path;

import info.mudbourn.mmsmetro.block.SpeedBumpBlock;
import info.mudbourn.mmsmetro.block.StationBlock;
import info.mudbourn.mmsmetro.block.entity.JunctionBlockEntity;
import info.mudbourn.mmsmetro.block.entity.SpeedBumpBlockEntity;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.RailShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// An along-track polyline resolved by walking vanilla rails, with arc-length sampling; cars are positioned by distance along this path, never by guessing.
public final class RailPath {

    // Default cap on how far a path is walked from its origin, in rail nodes.
    public static final int MAX_NODES = 512;

    private final List<Vec3d> points;

    // The rail block each node sits on, in path order, so markers can be re-scanned against the world without rebuilding the geometry (what makes live station placement work).
    private final List<BlockPos> nodes;

    private final double[] cumulative;

    private final double length;

    // Mutable: refreshed in place by refreshMarkers so arc positions stay fixed.
    private final List<PathStation> stations = new ArrayList<>();

    private final List<PathBump> bumps = new ArrayList<>();

    // True when the walked track returned to its start node: a continuous loop the train circles rather than an out-and-back line, its geometry carrying a closing segment so arc-length spans the whole ring.
    private final boolean loop;

    // Arc the loop folds back to when the head passes the end: zero for a ring closed at its start, the rejoin node's arc for a spur that feeds into a ring, so the lead-in spur is ridden once and the ring circled forever after.
    private final double loopStartArc;

    private RailPath(List<Vec3d> points, List<BlockPos> nodes,
                    List<StationMark> marks, List<BumpMark> bumpMarks, boolean loop, int loopStartIndex) {
        this.points = points;
        this.nodes = nodes;
        this.loop = loop;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            this.cumulative[i] = this.cumulative[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        this.length = points.size() < 2 ? 0.0 : this.cumulative[points.size() - 1];
        this.loopStartArc = loop && loopStartIndex > 0 && loopStartIndex < this.cumulative.length
            ? this.cumulative[loopStartIndex] : 0.0;
        resolveMarks(marks, bumpMarks);
        info.mudbourn.mmsmetro.MmsMetro.LOGGER.debug(
            "[path] loop={} length={} loopStartArc={}", loop, this.length, this.loopStartArc);
        for (PathStation s : this.stations) {
            info.mudbourn.mmsmetro.MmsMetro.LOGGER.debug(
                "[path]   station '{}' arc={} terminus={}", s.name(), s.arc(), s.terminus());
        }
    }

    public boolean isLoop() {
        return this.loop;
    }

    // Arc the head returns to after crossing the seam; the ring is the span from here to length().
    public double loopStartArc() {
        return this.loopStartArc;
    }

    // Rebuilds the station and bump lists from raw marks; arc positions come from the fixed cumulative table, so re-running never shifts the geometry.
    private void resolveMarks(List<StationMark> marks, List<BumpMark> bumpMarks) {
        this.stations.clear();
        for (StationMark mark : marks) {
            if (mark.nodeIndex < this.cumulative.length) {
                this.stations.add(new PathStation(
                    this.cumulative[mark.nodeIndex], mark.dwellTicks, mark.pos, mark.terminus,
                    mark.name, mark.line, mark.direction, mark.fixedDirection, mark.nextStation,
                    mark.exitDirection, mark.hub, mark.transferLine, mark.lineColor));
            }
        }
        // Order by arc so the consist can step through stops along the path; one block passed twice yields two stops out of scan order, so sorting is what keeps them in travel order.
        this.stations.sort((a, b) -> Double.compare(a.arc(), b.arc()));

        // Resolve each bump against the first matching station ahead of it; a bump only announces if there is a station further along to arrive at.
        this.bumps.clear();
        for (BumpMark bump : bumpMarks) {
            if (bump.nodeIndex >= this.cumulative.length) {
                continue;
            }
            double arc = this.cumulative[bump.nodeIndex];
            String heading = headingName(arc);
            // A case-sensitive "T_" prefix is terminus mode: the bump heralds the nearest terminus whose label matches the rest of the entry, so a bump before a turn-around names the direction the terminus departs on (its NORTHBOUND label) rather than the SOUTHBOUND heading the train arrives on.
            boolean terminusMode = bump.direction.startsWith("T_");
            String wanted = terminusMode ? bump.direction.substring(2) : bump.direction;
            // A directed bump heralds the first stop ahead whose direction readout it matches: the stop's fixed label when set, else the track's heading of travel here, so it fires on the right pass and lines up with fixed-label stations.
            PathStation ahead = null;
            for (PathStation station : this.stations) {
                if (station.arc() <= arc + 1.0e-3) {
                    continue;
                }
                if (!bumpMatches(terminusMode, wanted, station, heading)) {
                    continue;
                }
                ahead = station;
                break;
            }
            // On a ring a bump past the last station heralds the first matching station across the seam, so wrap to the start rather than dropping it.
            if (ahead == null && this.loop) {
                for (PathStation station : this.stations) {
                    if (!bumpMatches(terminusMode, wanted, station, heading)) {
                        continue;
                    }
                    ahead = station;
                    break;
                }
            }
            if (ahead == null) {
                continue;
            }
            this.bumps.add(new PathBump(arc, ahead.pos(), ahead.name(), ahead.exitDirection(),
                ahead.hub(), ahead.transferLine(), ahead.terminus()));
        }
    }

    // Re-scans every node for markers and rebuilds the lists, so stations placed or removed after the path was built register without respawning the train; geometry and arc positions are unchanged.
    public void refreshMarkers(World world) {
        List<StationMark> marks = new ArrayList<>();
        List<BumpMark> bumpMarks = new ArrayList<>();
        scanMarks(world, this.nodes, marks, bumpMarks);
        resolveMarks(marks, bumpMarks);
    }

    // Arc-length distance to the first station on this path, or +infinity if it reaches none; used to steer a train toward the nearer station.
    public double nearestStationArc() {
        return this.stations.isEmpty() ? Double.POSITIVE_INFINITY : this.stations.get(0).arc();
    }

    public double length() {
        return this.length;
    }

    // Station stops along this path, ordered from the head of the path.
    public List<PathStation> stations() {
        return this.stations;
    }

    // Announcement triggers along this path, ordered from the head of the path.
    public List<PathBump> bumps() {
        return this.bumps;
    }

    // A station found at a node, before arc-lengths are known.
    private record StationMark(int nodeIndex, int dwellTicks, BlockPos pos, boolean terminus,
                              String name, String line, String direction, boolean fixedDirection,
                              String nextStation, String exitDirection, boolean hub,
                              String transferLine, String lineColor) {
    }

    // A speed bump found at a node, before arc-lengths are known.
    private record BumpMark(int nodeIndex, String direction) {
    }

    public PathPoint sample(double s) {
        if (this.points.size() < 2) {
            Vec3d only = this.points.isEmpty() ? Vec3d.ZERO : this.points.get(0);
            return new PathPoint(only, 0.0f, 0.0f);
        }

        double clamped = Math.max(0.0, Math.min(this.length, s));
        int i = 0;
        while (i < this.cumulative.length - 2 && this.cumulative[i + 1] < clamped) {
            i++;
        }

        double segLength = this.cumulative[i + 1] - this.cumulative[i];
        double t = segLength > 1.0e-6 ? (clamped - this.cumulative[i]) / segLength : 0.0;

        Vec3d p1 = this.points.get(i);
        Vec3d p2 = this.points.get(i + 1);

        // Position follows the straight segment between rail block centers, so the train never cuts inside a corner and stays within the rail footprint.
        Vec3d pos = p1.add(p2.subtract(p1).multiply(t));
        Vec3d dir = normalizeOr(p2.subtract(p1), new Vec3d(0, 0, 1));

        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizontal));
        return new PathPoint(pos, yaw, pitch);
    }

    private static Vec3d normalizeOr(Vec3d v, Vec3d fallback) {
        return v.lengthSquared() < 1.0e-9 ? fallback : v.normalize();
    }

    // Compass heading of travel at an arc as a metro-style "*bound" label, or empty when the path is too short to have a direction.
    public String headingName(double arc) {
        Vec3d dir = directionAt(arc);
        if (dir == null) {
            return "";
        }
        if (Math.abs(dir.x) >= Math.abs(dir.z)) {
            return dir.x >= 0 ? "Eastbound" : "Westbound";
        }
        return dir.z >= 0 ? "Southbound" : "Northbound";
    }

    // Travel vector of the segment containing an arc, or null for a path with fewer than two points.
    private Vec3d directionAt(double s) {
        if (this.points.size() < 2) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(this.length, s));
        int i = 0;
        while (i < this.cumulative.length - 2 && this.cumulative[i + 1] < clamped) {
            i++;
        }
        return this.points.get(i + 1).subtract(this.points.get(i));
    }

    // The direction a train approaching a stop would display: the stop's fixed label when set, else the track's heading of travel; this is what a directed bump is matched against.
    private static String stationReadout(PathStation station, String heading) {
        return station.fixedDirection() && !station.direction().isEmpty()
            ? station.direction()
            : heading;
    }

    // True when a bump should herald this station: terminus mode also requires the station be a terminus, and either mode still matches the wanted label against the station's readout, with an empty label matching any.
    private static boolean bumpMatches(boolean terminusMode, String wanted, PathStation station, String heading) {
        if (terminusMode && !station.terminus()) {
            return false;
        }
        return directionMatches(wanted, stationReadout(station, heading));
    }

    // Matches a bump's typed direction against a heading: an empty direction matches any, else the typed word must prefix the heading's cardinal so "east" and "Eastbound" both match "Eastbound".
    private static boolean directionMatches(String typed, String heading) {
        if (typed.isEmpty()) {
            return true;
        }
        String t = typed.trim().toLowerCase().replace("bound", "").trim();
        String h = heading.toLowerCase().replace("bound", "");
        return !t.isEmpty() && h.startsWith(t);
    }

    public static RailPath build(World world, BlockPos start, Direction initialDir, int maxNodes) {
        List<Vec3d> points = new ArrayList<>();
        List<BlockPos> nodes = new ArrayList<>();
        List<StationMark> marks = new ArrayList<>();
        List<BumpMark> bumpMarks = new ArrayList<>();
        RailShape startShape = railShape(world, start);
        if (startShape == null) {
            return new RailPath(points, nodes, marks, bumpMarks, false, 0);
        }

        info.mudbourn.mmsmetro.MmsMetro.LOGGER.debug(
            "[path] build start {} shape {} initialDir {}", start, startShape, initialDir);
        points.add(centerPoint(start, startShape));
        nodes.add(start.toImmutable());
        Direction travel = pickExit(startShape, initialDir);
        // Keyed by node plus the heading the walk leaves it on, not node alone, so a junction crossed twice on different headings is a legitimate crossing rather than a premature loop; the value is the node's first index for closing the ring.
        Map<String, Integer> stateIndex = new HashMap<>();
        stateIndex.put(stateKey(start, travel), 0);
        BlockPos current = start;
        RailShape currentShape = startShape;
        boolean loop = false;
        int loopStartIndex = 0;
        String endReason = "maxNodes";

        for (int n = 0; n < maxNodes; n++) {
            BlockPos next = step(world, current, currentShape, travel);
            if (next == null) {
                endReason = "no rail " + travel + " of " + current;
                break;
            }

            RailShape nextShape = railShape(world, next);
            if (nextShape == null) {
                endReason = "not a rail at " + next;
                break;
            }

            // A junction block under the node overrides the greedy follow, routing by the direction the train arrived on so arrival and departure tracks stay separate; without one the walk picks the straightest exit.
            Direction exit = junctionExit(world, next, travel);
            if (exit == null) {
                // Vanilla routes a cart along the rail's two ends, taking the end its heading points toward (DefaultMinecartController.moveOnRail), never requiring a matching entry side; replicate that so curves entered from the open side round instead of dead-ending.
                Direction[] conns = connections(nextShape);
                exit = dot(conns[0], travel) >= dot(conns[1], travel) ? conns[0] : conns[1];
            }

            // Re-entering a node on the same exit heading repeats the whole future: that is the ring closing, so stop before re-adding it and fold the geometry back to where the state first occurred.
            String key = stateKey(next, exit);
            Integer seen = stateIndex.get(key);
            if (seen != null) {
                loop = true;
                loopStartIndex = seen;
                endReason = "loop at " + next + " heading " + exit;
                break;
            }

            points.add(centerPoint(next, nextShape));
            nodes.add(next.toImmutable());
            stateIndex.put(key, nodes.size() - 1);

            current = next;
            currentShape = nextShape;
            travel = exit;
        }

        // Close a loop's geometry with a final segment back to the rejoin node so arc-length covers the whole ring; only the sampled polyline is closed, not the node list, so markers are still scanned once per real rail block.
        if (loop && points.size() > 1) {
            points.add(points.get(loopStartIndex));
        }

        info.mudbourn.mmsmetro.MmsMetro.LOGGER.debug(
            "[path] build end: {} nodes, last {}, reason {}",
            nodes.size(), nodes.isEmpty() ? "none" : nodes.get(nodes.size() - 1), endReason);
        scanMarks(world, nodes, marks, bumpMarks);
        return new RailPath(points, nodes, marks, bumpMarks, loop, loopStartIndex);
    }

    // A run of node indices more than this far apart counts as a separate pass of the track past one marker, so a route that visits a block twice (an out-and-back stub, or a junction crossed on two headings) stops there on each pass.
    private static final int PASS_GAP = 8;

    // Scans every node's neighborhood for markers, then binds one stop per pass of the track: the nearest node within each run of nearby indices, so a block the route passes twice is served twice rather than pinned to a single arc.
    private static void scanMarks(World world, List<BlockPos> nodes,
                                  List<StationMark> outStations, List<BumpMark> outBumps) {
        int r = BUMP_H_RADIUS;
        Map<BlockPos, List<double[]>> stationHits = new java.util.LinkedHashMap<>();
        Map<BlockPos, List<double[]>> bumpHits = new java.util.LinkedHashMap<>();

        for (int i = 0; i < nodes.size(); i++) {
            BlockPos rail = nodes.get(i);
            for (BlockPos pos : BlockPos.iterate(
                    rail.add(-r, -STATION_DOWN, -r), rail.add(r, STATION_UP, r))) {
                double sq = pos.getSquaredDistance(rail);
                int hOffset = Math.max(Math.abs(pos.getX() - rail.getX()), Math.abs(pos.getZ() - rail.getZ()));
                if (hOffset <= STATION_H_RADIUS && world.getBlockState(pos).getBlock() instanceof StationBlock) {
                    stationHits.computeIfAbsent(pos.toImmutable(), p -> new ArrayList<>()).add(new double[]{i, sq});
                } else if (world.getBlockState(pos).getBlock() instanceof SpeedBumpBlock) {
                    bumpHits.computeIfAbsent(pos.toImmutable(), p -> new ArrayList<>()).add(new double[]{i, sq});
                }
            }
        }

        for (Map.Entry<BlockPos, List<double[]>> entry : stationHits.entrySet()) {
            for (int nodeIndex : nearestPerPass(entry.getValue())) {
                outStations.add(readStationMark(world, entry.getKey(), nodeIndex));
            }
        }
        for (Map.Entry<BlockPos, List<double[]>> entry : bumpHits.entrySet()) {
            BlockPos pos = entry.getKey();
            String direction = world.getBlockEntity(pos) instanceof SpeedBumpBlockEntity bump
                ? bump.getDirection() : "";
            for (int nodeIndex : nearestPerPass(entry.getValue())) {
                outBumps.add(new BumpMark(nodeIndex, direction));
            }
        }
    }

    // Splits one marker's hits into passes (runs of node indices no more than PASS_GAP apart) and returns the nearest node of each, so the marker binds once per time the track goes by it.
    private static List<Integer> nearestPerPass(List<double[]> hits) {
        hits.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
        List<Integer> result = new ArrayList<>();
        int bestNode = -1;
        double bestSq = Double.MAX_VALUE;
        int prevNode = Integer.MIN_VALUE;
        for (double[] hit : hits) {
            int nodeIndex = (int) hit[0];
            if (nodeIndex - prevNode > PASS_GAP && bestNode >= 0) {
                result.add(bestNode);
                bestNode = -1;
                bestSq = Double.MAX_VALUE;
            }
            if (hit[1] < bestSq) {
                bestSq = hit[1];
                bestNode = nodeIndex;
            }
            prevNode = nodeIndex;
        }
        if (bestNode >= 0) {
            result.add(bestNode);
        }
        return result;
    }

    // Reads a station block's settings into a mark bound to its nearest node.
    private static StationMark readStationMark(World world, BlockPos best, int nodeIndex) {
        int dwell = 100;
        boolean terminus = false;
        String name = "";
        String line = "";
        String direction = "";
        boolean fixedDirection = false;
        String nextStation = "";
        String exitDirection = "";
        boolean hub = false;
        String transferLine = "";
        String lineColor = "white";
        BlockEntity be = world.getBlockEntity(best);
        if (be instanceof StationBlockEntity station) {
            dwell = station.getDwellTicks();
            terminus = station.isTerminus();
            name = station.getStationName();
            line = station.getLineName();
            direction = station.getLineDirection();
            fixedDirection = station.isFixedDirection();
            nextStation = station.getNextStation();
            exitDirection = station.getExitDirection();
            hub = station.isHub();
            transferLine = station.getTransferLine();
            lineColor = station.getLineColor();
        }
        return new StationMark(nodeIndex, dwell, best, terminus,
            name, line, direction, fixedDirection, nextStation, exitDirection, hub, transferLine, lineColor);
    }

    // How far a station marker may sit from a rail node and still count: one block out horizontally, two below to two above; primary placement is under the rail, but beside or on a platform above also counts, bound to the single nearest node.
    private static final int STATION_H_RADIUS = 1;
    private static final int STATION_UP = 2;
    private static final int STATION_DOWN = 2;

    // Speed bumps reach four blocks out to either side of the track so a train at high speed never overshoots the node the bump binds to.
    private static final int BUMP_H_RADIUS = 4;

    // A walk-state identity: a node together with the heading the walk departs it on, so the loop closes only when the whole future would repeat.
    private static String stateKey(BlockPos pos, Direction heading) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + heading;
    }

    // The exit a junction block under this node dictates for a train arriving on the given heading, or null when there is no junction or it leaves that heading unrouted.
    private static Direction junctionExit(World world, BlockPos node, Direction travel) {
        BlockEntity be = world.getBlockEntity(node.down());
        if (be instanceof JunctionBlockEntity junction) {
            Direction exit = junction.getExit(travel);
            info.mudbourn.mmsmetro.MmsMetro.LOGGER.debug(
                "[junction] at {} train heading {} -> exit {}", node.down(), travel, exit);
            return exit;
        }
        return null;
    }

    private static RailShape railShape(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof AbstractRailBlock rail) {
            return state.get(rail.getShapeProperty());
        }
        return null;
    }

    private static Vec3d centerPoint(BlockPos pos, RailShape shape) {
        double y = pos.getY() + (shape.isAscending() ? 0.5 : 0.0625);
        return new Vec3d(pos.getX() + 0.5, y, pos.getZ() + 0.5);
    }

    private static BlockPos step(World world, BlockPos current, RailShape shape, Direction travel) {
        BlockPos horizontal = current.offset(travel);
        BlockPos[] candidates = travel == ascendingUp(shape)
            ? new BlockPos[]{horizontal.up(), horizontal, horizontal.down()}
            : new BlockPos[]{horizontal, horizontal.down(), horizontal.up()};

        for (BlockPos candidate : candidates) {
            if (AbstractRailBlock.isRail(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Direction pickExit(RailShape shape, Direction preferred) {
        Direction[] conns = connections(shape);
        if (conns[0] == preferred || conns[1] == preferred) {
            return preferred;
        }
        double d0 = dot(conns[0], preferred);
        double d1 = dot(conns[1], preferred);
        return d0 >= d1 ? conns[0] : conns[1];
    }

    private static double dot(Direction a, Direction b) {
        return a.getOffsetX() * b.getOffsetX() + a.getOffsetZ() * b.getOffsetZ();
    }

    private static Direction ascendingUp(RailShape shape) {
        return switch (shape) {
            case ASCENDING_EAST -> Direction.EAST;
            case ASCENDING_WEST -> Direction.WEST;
            case ASCENDING_NORTH -> Direction.NORTH;
            case ASCENDING_SOUTH -> Direction.SOUTH;
            default -> null;
        };
    }

    private static Direction[] connections(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new Direction[]{Direction.NORTH, Direction.SOUTH};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new Direction[]{Direction.EAST, Direction.WEST};
            case SOUTH_EAST -> new Direction[]{Direction.SOUTH, Direction.EAST};
            case SOUTH_WEST -> new Direction[]{Direction.SOUTH, Direction.WEST};
            case NORTH_WEST -> new Direction[]{Direction.NORTH, Direction.WEST};
            case NORTH_EAST -> new Direction[]{Direction.NORTH, Direction.EAST};
        };
    }
}
