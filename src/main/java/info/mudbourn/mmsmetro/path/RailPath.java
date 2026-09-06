package info.mudbourn.mmsmetro.path;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
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

    private final List<Vec3d> points;

    private final double[] cumulative;

    private final double length;

    private RailPath(List<Vec3d> points) {
        this.points = points;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            this.cumulative[i] = this.cumulative[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        this.length = points.size() < 2 ? 0.0 : this.cumulative[points.size() - 1];
    }

    public double length() {
        return this.length;
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
        Vec3d a = this.points.get(i);
        Vec3d b = this.points.get(i + 1);
        Vec3d pos = a.add(b.subtract(a).multiply(t));

        Vec3d dir = b.subtract(a);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizontal));
        return new PathPoint(pos, yaw, pitch);
    }

    public static RailPath build(World world, BlockPos start, Direction initialDir, int maxNodes) {
        List<Vec3d> points = new ArrayList<>();
        RailShape startShape = railShape(world, start);
        if (startShape == null) {
            return new RailPath(points);
        }

        points.add(centerPoint(start, startShape));
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

        return new RailPath(points);
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
