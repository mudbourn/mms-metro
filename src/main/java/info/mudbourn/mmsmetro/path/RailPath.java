package info.mudbourn.mmsmetro.path;

import info.mudbourn.mmsmetro.block.SpeedBumpBlock;
import info.mudbourn.mmsmetro.block.StationBlock;
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
import java.util.List;

// An along-track polyline resolved by walking vanilla rails, with arc-length
// sampling. Cars are positioned by distance along this path, never by guessing.
public final class RailPath {

    // Default cap on how far a path is walked from its origin, in rail nodes.
    public static final int MAX_NODES = 512;

    private final List<Vec3d> points;

    private final double[] cumulative;

    private final double length;

    private final List<PathStation> stations;

    private final List<PathBump> bumps;

    private RailPath(List<Vec3d> points, List<StationMark> marks, List<BumpMark> bumpMarks) {
        this.points = points;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            this.cumulative[i] = this.cumulative[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        this.length = points.size() < 2 ? 0.0 : this.cumulative[points.size() - 1];

        this.stations = new ArrayList<>();
        for (StationMark mark : marks) {
            if (mark.nodeIndex < this.cumulative.length) {
                this.stations.add(new PathStation(
                    this.cumulative[mark.nodeIndex], mark.dwellTicks, mark.pos, mark.terminus,
                    mark.name, mark.line, mark.direction, mark.nextStation,
                    mark.exitDirection, mark.hub, mark.transferLine));
            }
        }

        // Resolve each bump against the first station ahead of it: the bump only
        // announces if there is a station further along to arrive at.
        this.bumps = new ArrayList<>();
        for (BumpMark bump : bumpMarks) {
            if (bump.nodeIndex >= this.cumulative.length) {
                continue;
            }
            double arc = this.cumulative[bump.nodeIndex];
            PathStation ahead = null;
            for (PathStation station : this.stations) {
                if (station.arc() > arc + 1.0e-3) {
                    ahead = station;
                    break;
                }
            }
            if (ahead == null) {
                continue;
            }
            this.bumps.add(new PathBump(arc, ahead.name(), ahead.exitDirection(),
                ahead.hub(), ahead.transferLine(), bump.terminus || ahead.terminus()));
        }
    }

    // Arc-length distance to the first station on this path, or +infinity if the
    // path reaches no station. Used to steer a train toward the nearer station.
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
    private record BumpMark(int nodeIndex, boolean terminus) {
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

        // Catmull-Rom through the four nodes around this segment: the curve still
        // passes through every node but rounds the corners between them, and its
        // tangent gives a heading that eases through turns instead of snapping.
        Vec3d p0 = this.points.get(Math.max(0, i - 1));
        Vec3d p1 = this.points.get(i);
        Vec3d p2 = this.points.get(i + 1);
        Vec3d p3 = this.points.get(Math.min(this.points.size() - 1, i + 2));

        Vec3d pos = catmullRom(p0, p1, p2, p3, t);
        Vec3d dir = catmullRomTangent(p0, p1, p2, p3, t);
        if (dir.lengthSquared() < 1.0e-9) {
            dir = p2.subtract(p1);
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizontal));
        return new PathPoint(pos, yaw, pitch);
    }

    // Standard (uniform, tension 0.5) Catmull-Rom interpolation at t in [0,1].
    private static Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t
            + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
            + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
        double y = 0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t
            + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
            + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
        double z = 0.5 * ((2 * p1.z) + (-p0.z + p2.z) * t
            + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2
            + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);
        return new Vec3d(x, y, z);
    }

    // Derivative of the Catmull-Rom curve above, used as the heading tangent.
    private static Vec3d catmullRomTangent(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double x = 0.5 * ((-p0.x + p2.x)
            + 2 * (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t
            + 3 * (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t2);
        double y = 0.5 * ((-p0.y + p2.y)
            + 2 * (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t
            + 3 * (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t2);
        double z = 0.5 * ((-p0.z + p2.z)
            + 2 * (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t
            + 3 * (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t2);
        return new Vec3d(x, y, z);
    }

    public static RailPath build(World world, BlockPos start, Direction initialDir, int maxNodes) {
        List<Vec3d> points = new ArrayList<>();
        List<StationMark> marks = new ArrayList<>();
        List<BumpMark> bumpMarks = new ArrayList<>();
        java.util.Set<BlockPos> claimed = new java.util.HashSet<>();
        java.util.Set<BlockPos> claimedBumps = new java.util.HashSet<>();
        RailShape startShape = railShape(world, start);
        if (startShape == null) {
            return new RailPath(points, marks, bumpMarks);
        }

        points.add(centerPoint(start, startShape));
        addStationMark(world, start, points.size() - 1, marks, claimed);
        addBumpMark(world, start, points.size() - 1, bumpMarks, claimedBumps);
        Direction travel = pickExit(startShape, initialDir);
        BlockPos current = start;
        RailShape currentShape = startShape;

        for (int n = 0; n < maxNodes; n++) {
            BlockPos next = step(world, current, currentShape, travel);
            if (next == null || (next.equals(start) && n > 0)) {
                break;
            }

            RailShape nextShape = railShape(world, next);
            if (nextShape == null) {
                break;
            }

            points.add(centerPoint(next, nextShape));
            addStationMark(world, next, points.size() - 1, marks, claimed);
            addBumpMark(world, next, points.size() - 1, bumpMarks, claimedBumps);
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

        return new RailPath(points, marks, bumpMarks);
    }

    // How far a station block may sit from a rail node and still count: one
    // block out horizontally (a platform marker beside the track) and up to two
    // blocks up. The nearest node claims each block so it is only marked once.
    private static final int STATION_H_RADIUS = 1;
    private static final int STATION_UP = 2;

    // Records a station stop for the nearest StationBlock around this rail node.
    private static void addStationMark(World world, BlockPos rail, int nodeIndex,
                                       List<StationMark> marks, java.util.Set<BlockPos> claimed) {
        int r = STATION_H_RADIUS;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(
                rail.add(-r, 0, -r), rail.add(r, STATION_UP, r))) {
            if (claimed.contains(pos.toImmutable())
                || !(world.getBlockState(pos).getBlock() instanceof StationBlock)) {
                continue;
            }
            double sq = pos.getSquaredDistance(rail);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos.toImmutable();
            }
        }

        if (best == null) {
            return;
        }

        claimed.add(best);
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
        marks.add(new StationMark(nodeIndex, dwell, best, terminus,
            name, line, direction, nextStation, exitDirection, hub, transferLine));
    }

    // Records a speed bump found around this rail node, using the same
    // neighborhood scan as stations so a bump may sit beside or under the track.
    private static void addBumpMark(World world, BlockPos rail, int nodeIndex,
                                    List<BumpMark> marks, java.util.Set<BlockPos> claimed) {
        int r = STATION_H_RADIUS;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(
                rail.add(-r, -1, -r), rail.add(r, STATION_UP, r))) {
            if (claimed.contains(pos.toImmutable())
                || !(world.getBlockState(pos).getBlock() instanceof SpeedBumpBlock)) {
                continue;
            }
            double sq = pos.getSquaredDistance(rail);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos.toImmutable();
            }
        }

        if (best == null) {
            return;
        }

        claimed.add(best);
        boolean terminus = world.getBlockState(best).get(SpeedBumpBlock.TERMINUS);
        marks.add(new BumpMark(nodeIndex, terminus));
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
