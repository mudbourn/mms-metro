package info.mudbourn.mmsmetro.path;

import info.mudbourn.mmsmetro.block.SpeedBumpBlock;
import info.mudbourn.mmsmetro.block.StationBlock;
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

    private RailPath(List<Vec3d> points, List<BlockPos> nodes,
                    List<StationMark> marks, List<BumpMark> bumpMarks, boolean loop) {
        this.points = points;
        this.nodes = nodes;
        this.loop = loop;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            this.cumulative[i] = this.cumulative[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        this.length = points.size() < 2 ? 0.0 : this.cumulative[points.size() - 1];
        resolveMarks(marks, bumpMarks);
    }

    public boolean isLoop() {
        return this.loop;
    }

    // Rebuilds the station and bump lists from raw marks; arc positions come from the fixed cumulative table, so re-running never shifts the geometry.
    private void resolveMarks(List<StationMark> marks, List<BumpMark> bumpMarks) {
        this.stations.clear();
        for (StationMark mark : marks) {
            if (mark.nodeIndex < this.cumulative.length) {
                this.stations.add(new PathStation(
                    this.cumulative[mark.nodeIndex], mark.dwellTicks, mark.pos, mark.terminus,
                    mark.name, mark.line, mark.direction, mark.nextStation,
                    mark.exitDirection, mark.hub, mark.transferLine));
            }
        }

        // Resolve each bump against the first matching station ahead of it; a bump only announces if there is a station further along to arrive at.
        this.bumps.clear();
        for (BumpMark bump : bumpMarks) {
            if (bump.nodeIndex >= this.cumulative.length) {
                continue;
            }
            double arc = this.cumulative[bump.nodeIndex];
            PathStation ahead = null;
            for (PathStation station : this.stations) {
                if (station.arc() <= arc + 1.0e-3) {
                    continue;
                }
                // A directed bump only heralds a stop on its own side, so a nearer opposite-direction platform never counts.
                if (!bump.direction.isEmpty() && !bump.direction.equalsIgnoreCase(station.direction())) {
                    continue;
                }
                ahead = station;
                break;
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
                              String name, String line, String direction, String nextStation,
                              String exitDirection, boolean hub, String transferLine) {
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

    public static RailPath build(World world, BlockPos start, Direction initialDir, int maxNodes) {
        List<Vec3d> points = new ArrayList<>();
        List<BlockPos> nodes = new ArrayList<>();
        List<StationMark> marks = new ArrayList<>();
        List<BumpMark> bumpMarks = new ArrayList<>();
        RailShape startShape = railShape(world, start);
        if (startShape == null) {
            return new RailPath(points, nodes, marks, bumpMarks, false);
        }

        points.add(centerPoint(start, startShape));
        nodes.add(start.toImmutable());
        Direction travel = pickExit(startShape, initialDir);
        BlockPos current = start;
        RailShape currentShape = startShape;
        boolean loop = false;

        for (int n = 0; n < maxNodes; n++) {
            BlockPos next = step(world, current, currentShape, travel);
            if (next == null) {
                break;
            }
            // Walking back onto the start node closes the ring: record the loop and stop before re-adding start, which is already node zero.
            if (next.equals(start) && n > 0) {
                loop = true;
                break;
            }

            RailShape nextShape = railShape(world, next);
            if (nextShape == null) {
                break;
            }

            points.add(centerPoint(next, nextShape));
            nodes.add(next.toImmutable());
            Direction entered = travel.getOpposite();
            Direction[] conns = connections(nextShape);
            Direction exit = conns[0] == entered ? conns[1] : (conns[1] == entered ? conns[0] : null);
            if (exit == null) {
                break;
            }

            current = next;
            currentShape = nextShape;
            travel = exit;
        }

        // Close a loop's geometry with a final segment back to the start so arc-length covers the whole ring; only the sampled polyline is closed, not the node list, so markers are still scanned once per real rail block.
        if (loop && points.size() > 1) {
            points.add(points.get(0));
        }

        scanMarks(world, nodes, marks, bumpMarks);
        return new RailPath(points, nodes, marks, bumpMarks, loop);
    }

    // Scans every node's neighborhood for markers, binding each block to the single closest node; nearest-node assignment (not first-node-wins) is what centres a train on the station rather than braking a node short of it.
    private static void scanMarks(World world, List<BlockPos> nodes,
                                  List<StationMark> outStations, List<BumpMark> outBumps) {
        int r = STATION_H_RADIUS;
        Map<BlockPos, int[]> stationNode = new java.util.LinkedHashMap<>();
        Map<BlockPos, Double> stationDist = new HashMap<>();
        Map<BlockPos, int[]> bumpNode = new java.util.LinkedHashMap<>();
        Map<BlockPos, Double> bumpDist = new HashMap<>();

        for (int i = 0; i < nodes.size(); i++) {
            BlockPos rail = nodes.get(i);
            for (BlockPos pos : BlockPos.iterate(
                    rail.add(-r, -STATION_DOWN, -r), rail.add(r, STATION_UP, r))) {
                double sq = pos.getSquaredDistance(rail);
                if (world.getBlockState(pos).getBlock() instanceof StationBlock) {
                    considerClosest(pos.toImmutable(), i, sq, stationNode, stationDist);
                } else if (world.getBlockState(pos).getBlock() instanceof SpeedBumpBlock) {
                    considerClosest(pos.toImmutable(), i, sq, bumpNode, bumpDist);
                }
            }
        }

        for (Map.Entry<BlockPos, int[]> entry : stationNode.entrySet()) {
            outStations.add(readStationMark(world, entry.getKey(), entry.getValue()[0]));
        }
        for (Map.Entry<BlockPos, int[]> entry : bumpNode.entrySet()) {
            BlockPos pos = entry.getKey();
            String direction = world.getBlockEntity(pos) instanceof SpeedBumpBlockEntity bump
                ? bump.getDirection() : "";
            outBumps.add(new BumpMark(entry.getValue()[0], direction));
        }
    }

    // Keeps, for one marker block, the nearest node seen so far.
    private static void considerClosest(BlockPos pos, int nodeIndex, double sq,
                                        Map<BlockPos, int[]> node, Map<BlockPos, Double> dist) {
        Double best = dist.get(pos);
        if (best == null || sq < best) {
            dist.put(pos, sq);
            node.put(pos, new int[]{nodeIndex});
        }
    }

    // Reads a station block's settings into a mark bound to its nearest node.
    private static StationMark readStationMark(World world, BlockPos best, int nodeIndex) {
        int dwell = 100;
        boolean terminus = false;
        String name = "";
        String line = "";
        String direction = "";
        String nextStation = "";
        String exitDirection = "";
        boolean hub = false;
        String transferLine = "";
        BlockEntity be = world.getBlockEntity(best);
        if (be instanceof StationBlockEntity station) {
            dwell = station.getDwellTicks();
            terminus = station.isTerminus();
            name = station.getStationName();
            line = station.getLineName();
            direction = station.getLineDirection();
            nextStation = station.getNextStation();
            exitDirection = station.getExitDirection();
            hub = station.isHub();
            transferLine = station.getTransferLine();
        }
        return new StationMark(nodeIndex, dwell, best, terminus,
            name, line, direction, nextStation, exitDirection, hub, transferLine);
    }

    // How far a marker may sit from a rail node and still count: one block out horizontally, two below to two above; primary placement is under the rail, but beside or on a platform above also counts, bound to the single nearest node.
    private static final int STATION_H_RADIUS = 1;
    private static final int STATION_UP = 2;
    private static final int STATION_DOWN = 2;

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
