package info.mudbourn.mmsmetro.path;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.entity.JunctionBlockEntity;
import info.mudbourn.mmsmetro.block.entity.SpeakerBlockEntity;
import info.mudbourn.mmsmetro.block.entity.SpeedBumpBlockEntity;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.RailShape;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// An along-track polyline resolved by walking vanilla rails, with arc-length sampling and the markers baked onto it.
public final class RailPath {

    // Default cap on how far a path is walked from its origin, in rail nodes.
    public static final int MAX_NODES = 4096;

    // Radius, in blocks, to search around a station for a speaker block.
    private static final int SPEAKER_SEARCH_RADIUS = 5;

    // Radius, in blocks, to search around a junction for the station block whose platform decides priority.
    private static final int JUNCTION_STATION_RADIUS = 16;

    // Blocks around every node whose chunks can change the baked markers.
    private static final int NODE_CHUNK_MARGIN = 6;

    // Reverse points within this many blocks of the head count as the one the train stands at.
    private static final double REVERSE_REACH = 1.5;

    private final PathKey key;

    private final List<Vec3d> points;

    private final List<BlockPos> nodes;

    private final double[] cumulative;

    private final double length;

    private final List<PathStation> stations = new ArrayList<>();

    private final List<PathBump> bumps = new ArrayList<>();

    private final List<PathJunction> junctions = new ArrayList<>();

    private final List<PathReverse> reverses = new ArrayList<>();

    private final boolean loop;

    private final int loopStartIndex;

    // Arc the head returns to after crossing the loop seam.
    private final double loopStartArc;

    private LongSet chunks;

    private Map<BlockPos, List<Double>> nodeArcsByPos;

    private RailPath(PathKey key, List<Vec3d> points, List<BlockPos> nodes, boolean loop, int loopStartIndex) {
        this.key = key;
        this.points = points;
        this.nodes = nodes;
        this.loop = loop;
        this.loopStartIndex = loopStartIndex;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            this.cumulative[i] = this.cumulative[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        this.length = points.size() < 2 ? 0.0 : this.cumulative[points.size() - 1];
        this.loopStartArc = loop && loopStartIndex > 0 && loopStartIndex < this.cumulative.length
            ? this.cumulative[loopStartIndex] : 0.0;
    }

    public PathKey key() {
        return this.key;
    }

    public boolean isLoop() {
        return this.loop;
    }

    public double loopStartArc() {
        return this.loopStartArc;
    }

    public double length() {
        return this.length;
    }

    public List<PathStation> stations() {
        return this.stations;
    }

    public List<PathBump> bumps() {
        return this.bumps;
    }

    public List<PathJunction> junctions() {
        return this.junctions;
    }

    public List<PathReverse> reverses() {
        return this.reverses;
    }

    // The rail directions leaving a block, empty when it is not a rail.
    public static List<Direction> railExits(World world, BlockPos pos) {
        RailShape shape = railShape(world, pos);
        if (shape == null) {
            return List.of();
        }
        return List.of(connections(shape));
    }

    // The chunks whose block changes can alter this path.
    public LongSet chunks() {
        if (this.chunks == null) {
            LongSet set = new LongOpenHashSet();
            for (BlockPos node : this.nodes) {
                addChunks(set, node, NODE_CHUNK_MARGIN);
            }
            for (PathJunction junction : this.junctions) {
                addChunks(set, junction.rail(), JUNCTION_STATION_RADIUS);
            }
            this.chunks = set;
        }
        return this.chunks;
    }

    private static void addChunks(LongSet set, BlockPos center, int radius) {
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                set.add(ChunkPos.toLong(cx, cz));
            }
        }
    }

    // Arcs at which this path runs through pos, allowing one block of vertical tolerance.
    public List<Double> arcsOnTrack(BlockPos pos) {
        if (this.nodeArcsByPos == null) {
            Map<BlockPos, List<Double>> map = new HashMap<>();
            for (int i = 0; i < this.nodes.size() && i < this.cumulative.length; i++) {
                map.computeIfAbsent(this.nodes.get(i), k -> new ArrayList<>())
                    .add(this.cumulative[i]);
            }
            this.nodeArcsByPos = map;
        }
        List<Double> out = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            List<Double> arcs = this.nodeArcsByPos.get(pos.add(0, dy, 0));
            if (arcs != null) {
                out.addAll(arcs);
            }
        }
        return out;
    }

    // Index of the rail node nearest an arc.
    private int nodeIndexAt(double arc) {
        if (this.nodes.isEmpty()) {
            return -1;
        }
        if (this.points.size() < 2) {
            return 0;
        }
        double clamped = Math.max(0.0, Math.min(this.length, arc));
        int i = segmentIndex(clamped);
        int nearest = clamped - this.cumulative[i] <= this.cumulative[i + 1] - clamped ? i : i + 1;
        return Math.min(nearest, this.nodes.size() - 1);
    }

    // The rail node nearest an arc.
    public BlockPos nodeAt(double arc) {
        int index = nodeIndexAt(arc);
        return index < 0 ? this.key.origin() : this.nodes.get(index);
    }

    // The block a car sampled at an arc sits in.
    public BlockPos blockAt(double arc) {
        return BlockPos.ofFloored(sample(arc).pos());
    }

    // Arc on this path of the rail node an arc on another path sits at, or NaN when this path no longer runs through it.
    public double arcMatching(RailPath other, double otherArc) {
        int otherIndex = other.nodeIndexAt(otherArc);
        if (otherIndex < 0) {
            return Double.NaN;
        }
        BlockPos node = other.nodes.get(otherIndex);
        double otherNodeArc = other.cumulative[otherIndex];
        double best = Double.NaN;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < this.nodes.size() && i < this.cumulative.length; i++) {
            double gap = Math.abs(this.cumulative[i] - otherNodeArc);
            if (this.nodes.get(i).equals(node) && gap < bestGap) {
                bestGap = gap;
                best = this.cumulative[i];
            }
        }
        if (Double.isNaN(best)) {
            return best;
        }
        return Math.max(0.0, Math.min(this.length, best + (otherArc - otherNodeArc)));
    }

    // Records where this path turns a train around: every terminus station and the far end.
    public void bakeReverses() {
        this.reverses.clear();
        if (this.length <= 0.0) {
            return;
        }
        for (PathStation station : this.stations) {
            if (station.terminus()) {
                addReverse(station.arc());
            }
        }
        addReverse(this.length);
    }

    private void addReverse(double arc) {
        Direction back = Direction.fromHorizontalDegrees(sample(arc).yaw()).getOpposite();
        PathReverse reverse = new PathReverse(arc, new PathKey(nodeAt(arc), back));
        for (PathReverse existing : this.reverses) {
            if (existing.key().equals(reverse.key())) {
                return;
            }
        }
        this.reverses.add(reverse);
    }

    // The turn-around the head stands at, else the one at the far end, or null when none is baked.
    public PathReverse reverseAt(double arc) {
        PathReverse best = null;
        for (PathReverse reverse : this.reverses) {
            if (Math.abs(reverse.arc() - arc) <= REVERSE_REACH
                && (best == null || Math.abs(reverse.arc() - arc) < Math.abs(best.arc() - arc))) {
                best = reverse;
            }
        }
        if (best == null && !this.reverses.isEmpty()) {
            best = this.reverses.get(this.reverses.size() - 1);
        }
        return best;
    }

    // Serializes the geometry, markers and turn-arounds.
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.put("Key", this.key.toNbt());
        long[] nodeLongs = new long[this.nodes.size()];
        byte[] ascending = new byte[this.nodes.size()];
        for (int i = 0; i < this.nodes.size(); i++) {
            nodeLongs[i] = this.nodes.get(i).asLong();
            ascending[i] = (byte) (this.points.get(i).y - this.nodes.get(i).getY() > 0.25 ? 1 : 0);
        }
        nbt.putLongArray("Nodes", nodeLongs);
        nbt.putByteArray("Ascending", ascending);
        nbt.putBoolean("Loop", this.loop);
        nbt.putInt("LoopStart", this.loopStartIndex);

        NbtList stationList = new NbtList();
        for (PathStation station : this.stations) {
            stationList.add(station.toNbt());
        }
        nbt.put("Stations", stationList);

        NbtList bumpList = new NbtList();
        for (PathBump bump : this.bumps) {
            bumpList.add(bump.toNbt());
        }
        nbt.put("Bumps", bumpList);

        NbtList junctionList = new NbtList();
        for (PathJunction junction : this.junctions) {
            junctionList.add(junction.toNbt());
        }
        nbt.put("Junctions", junctionList);

        NbtList reverseList = new NbtList();
        for (PathReverse reverse : this.reverses) {
            reverseList.add(reverse.toNbt());
        }
        nbt.put("Reverses", reverseList);
        return nbt;
    }

    public static RailPath fromNbt(NbtCompound nbt) {
        long[] nodeLongs = nbt.getLongArray("Nodes").orElse(new long[0]);
        byte[] ascending = nbt.getByteArray("Ascending").orElse(new byte[0]);
        List<BlockPos> nodes = new ArrayList<>();
        List<Vec3d> points = new ArrayList<>();
        for (int i = 0; i < nodeLongs.length; i++) {
            BlockPos node = BlockPos.fromLong(nodeLongs[i]);
            nodes.add(node);
            points.add(centerPoint(node, i < ascending.length && ascending[i] != 0));
        }
        boolean loop = nbt.getBoolean("Loop", false);
        int loopStartIndex = nbt.getInt("LoopStart", 0);
        if (loop && points.size() > 1) {
            points.add(points.get(loopStartIndex));
        }

        RailPath path = new RailPath(PathKey.fromNbt(nbt.getCompoundOrEmpty("Key")), points, nodes, loop, loopStartIndex);
        NbtList stationList = nbt.getListOrEmpty("Stations");
        for (int i = 0; i < stationList.size(); i++) {
            path.stations.add(PathStation.fromNbt(stationList.getCompoundOrEmpty(i)));
        }
        NbtList bumpList = nbt.getListOrEmpty("Bumps");
        for (int i = 0; i < bumpList.size(); i++) {
            path.bumps.add(PathBump.fromNbt(bumpList.getCompoundOrEmpty(i)));
        }
        NbtList junctionList = nbt.getListOrEmpty("Junctions");
        for (int i = 0; i < junctionList.size(); i++) {
            path.junctions.add(PathJunction.fromNbt(junctionList.getCompoundOrEmpty(i)));
        }
        NbtList reverseList = nbt.getListOrEmpty("Reverses");
        for (int i = 0; i < reverseList.size(); i++) {
            path.reverses.add(PathReverse.fromNbt(reverseList.getCompoundOrEmpty(i)));
        }
        return path;
    }

    // Rebuilds the station and bump lists from raw marks; arc positions come from the fixed cumulative table.
    private void resolveMarks(List<StationMark> marks, List<BumpMark> bumpMarks) {
        this.stations.clear();
        for (StationMark mark : marks) {
            if (mark.nodeIndex < this.cumulative.length) {
                this.stations.add(new PathStation(
                    this.cumulative[mark.nodeIndex], mark.dwellTicks, mark.pos, mark.terminus,
                    mark.name, mark.line, mark.direction, mark.nextStation,
                    mark.exitDirection, mark.hub, mark.transferLine, mark.lineColor, mark.speaker));
            }
        }
        this.stations.sort((a, b) -> Double.compare(a.arc(), b.arc()));

        this.bumps.clear();
        for (BumpMark bump : bumpMarks) {
            if (bump.nodeIndex >= this.cumulative.length) {
                continue;
            }
            double arc = this.cumulative[bump.nodeIndex];
            String heading = headingName(arc);
            PathStation ahead = null;
            for (PathStation station : this.stations) {
                if (station.arc() <= arc + 1.0e-3) {
                    continue;
                }
                if (!bumpHeralds(bump, station, heading)) {
                    continue;
                }
                ahead = station;
                break;
            }
            if (ahead == null && this.loop) {
                for (PathStation station : this.stations) {
                    if (!bumpHeralds(bump, station, heading)) {
                        continue;
                    }
                    ahead = station;
                    break;
                }
            }
            if (ahead == null) {
                continue;
            }
            if (bump.direction.startsWith("T_") && ahead.terminus() && ahead.direction().isEmpty()) {
                String returnDir = bump.direction.substring(2);
                if (!returnDir.isEmpty()) {
                    int idx = this.stations.indexOf(ahead);
                    ahead = withDirection(ahead, returnDir);
                    this.stations.set(idx, ahead);
                }
            }
            this.bumps.add(new PathBump(arc, ahead.pos(), ahead.name(), ahead.exitDirection(),
                ahead.hub(), ahead.transferLine(), ahead.terminus(), ahead.speaker()));
        }
    }

    // Copies a station with a new direction label.
    private static PathStation withDirection(PathStation station, String direction) {
        return new PathStation(
            station.arc(), station.dwellTicks(), station.pos(), station.terminus(),
            station.name(), station.line(), direction, station.nextStation(),
            station.exitDirection(), station.hub(), station.transferLine(), station.lineColor(),
            station.speaker());
    }

    // Tether keys of the stops within a distance of a point, for the speed-bump editor dropdown.
    public List<String> stationKeysWithin(BlockPos center, double maxDistanceSq) {
        List<String> keys = new ArrayList<>();
        for (PathStation station : this.stations) {
            if (station.pos().getSquaredDistance(center) <= maxDistanceSq) {
                keys.add(stationKey(station));
            }
        }
        return keys;
    }

    // A station found at a node, before arc-lengths are known.
    private record StationMark(int nodeIndex, int dwellTicks, BlockPos pos, boolean terminus,
                              String name, String line, String direction,
                              String nextStation, String exitDirection, boolean hub,
                              String transferLine, String lineColor, BlockPos speaker) {
    }

    // A speed bump found at a node, before arc-lengths are known.
    private record BumpMark(int nodeIndex, String direction, String stationKey) {
    }

    public PathPoint sample(double s) {
        if (this.points.size() < 2) {
            Vec3d only = this.points.isEmpty() ? Vec3d.ZERO : this.points.get(0);
            return new PathPoint(only, 0.0f, 0.0f);
        }

        double clamped = Math.max(0.0, Math.min(this.length, s));
        int i = segmentIndex(clamped);

        double segLength = this.cumulative[i + 1] - this.cumulative[i];
        double t = segLength > 1.0e-6 ? (clamped - this.cumulative[i]) / segLength : 0.0;

        Vec3d p1 = this.points.get(i);
        Vec3d p2 = this.points.get(i + 1);

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

    // Compass heading of travel at an arc as an all-caps "*BOUND" label, or empty when the path has no direction.
    public String headingName(double arc) {
        Vec3d dir = directionAt(arc);
        if (dir == null) {
            return "";
        }
        if (Math.abs(dir.x) >= Math.abs(dir.z)) {
            return dir.x >= 0 ? "EASTBOUND" : "WESTBOUND";
        }
        return dir.z >= 0 ? "SOUTHBOUND" : "NORTHBOUND";
    }

    private Vec3d directionAt(double s) {
        if (this.points.size() < 2) {
            return null;
        }
        int i = segmentIndex(Math.max(0.0, Math.min(this.length, s)));
        return this.points.get(i + 1).subtract(this.points.get(i));
    }

    // Index of the segment holding a clamped arc.
    private int segmentIndex(double clamped) {
        int lo = 0;
        int hi = this.cumulative.length - 2;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (this.cumulative[mid + 1] < clamped) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static String stationReadout(PathStation station, String heading) {
        return !station.direction().isEmpty()
            ? station.direction()
            : heading;
    }

    private boolean bumpHeralds(BumpMark bump, PathStation station, String heading) {
        if (!bump.stationKey.isEmpty()) {
            return bump.stationKey.equals(stationKey(station));
        }
        boolean terminusMode = bump.direction.startsWith("T_");
        String wanted = terminusMode ? bump.direction.substring(2) : bump.direction;
        return bumpMatches(terminusMode, wanted, station, heading);
    }

    // The tether key a bump targets a station by: the alphanumeric station name, an underscore, then the first letter of its direction readout.
    private String stationKey(PathStation station) {
        String name = NON_ALPHANUMERIC.matcher(station.name()).replaceAll("");
        String readout = stationReadout(station, headingName(station.arc()));
        if (readout.isEmpty()) {
            return name + "_";
        }
        return name + "_" + readout.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");

    private static boolean bumpMatches(boolean terminusMode, String wanted, PathStation station, String heading) {
        if (terminusMode && !station.terminus()) {
            return false;
        }
        return directionMatches(wanted, stationReadout(station, heading));
    }

    private static boolean directionMatches(String typed, String heading) {
        if (typed.isEmpty()) {
            return true;
        }
        String t = typed.trim().toLowerCase().replace("bound", "").trim();
        String h = heading.toLowerCase().replace("bound", "");
        return !t.isEmpty() && h.startsWith(t);
    }

    // Reads blocks for a walk, optionally refusing to touch chunks that are not loaded.
    private static final class Walk {

        private final World world;

        private final boolean strict;

        private boolean missed;

        private Walk(World world, boolean strict) {
            this.world = world;
            this.strict = strict;
        }

        private boolean loaded(BlockPos pos) {
            return chunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        }

        private boolean chunkLoaded(int cx, int cz) {
            if (!this.strict || this.world.getChunkManager().isChunkLoaded(cx, cz)) {
                return true;
            }
            this.missed = true;
            return false;
        }

        private boolean boxLoaded(BlockPos center, int radius) {
            boolean all = true;
            for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
                for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                    all &= chunkLoaded(cx, cz);
                }
            }
            return all;
        }
    }

    // Walks the rails from a start block and bakes the path, loading chunks as needed.
    public static RailPath build(World world, BlockPos start, Direction initialDir, int maxNodes) {
        return walkRails(new Walk(world, false), start, initialDir, maxNodes);
    }

    // Like build, but returns null instead of loading a chunk that is not loaded.
    public static RailPath buildLoaded(World world, BlockPos start, Direction initialDir, int maxNodes) {
        Walk walk = new Walk(world, true);
        RailPath path = walkRails(walk, start, initialDir, maxNodes);
        return walk.missed ? null : path;
    }

    private static RailPath walkRails(Walk walk, BlockPos start, Direction initialDir, int maxNodes) {
        World world = walk.world;
        List<Vec3d> points = new ArrayList<>();
        List<BlockPos> nodes = new ArrayList<>();
        List<StationMark> marks = new ArrayList<>();
        List<BumpMark> bumpMarks = new ArrayList<>();
        List<Integer> junctionIndices = new ArrayList<>();
        PathKey key = new PathKey(start.toImmutable(), initialDir);
        RailShape startShape = railShape(walk, start);
        if (startShape == null) {
            return new RailPath(key, points, nodes, false, 0);
        }

        points.add(centerPoint(start, startShape.isAscending()));
        nodes.add(start.toImmutable());
        if (hasJunction(walk, start)) {
            junctionIndices.add(0);
        }
        Direction travel = pickExit(startShape, initialDir);
        Map<WalkState, Integer> stateIndex = new HashMap<>();
        stateIndex.put(new WalkState(start.toImmutable(), travel), 0);
        BlockPos current = start;
        RailShape currentShape = startShape;
        boolean loop = false;
        int loopStartIndex = 0;

        for (int n = 0; n < maxNodes; n++) {
            BlockPos next = step(walk, current, currentShape, travel);
            if (next == null) {
                break;
            }

            RailShape nextShape = railShape(walk, next);
            if (nextShape == null) {
                break;
            }

            Direction exit = junctionExit(walk, next, travel);
            if (exit == null) {
                Direction[] conns = connections(nextShape);
                exit = dot(conns[0], travel) >= dot(conns[1], travel) ? conns[0] : conns[1];
            }

            WalkState walkState = new WalkState(next.toImmutable(), exit);
            Integer seen = stateIndex.get(walkState);
            if (seen != null) {
                loop = true;
                loopStartIndex = seen;
                break;
            }

            points.add(centerPoint(next, nextShape.isAscending()));
            nodes.add(next.toImmutable());
            if (hasJunction(walk, next)) {
                junctionIndices.add(nodes.size() - 1);
            }
            stateIndex.put(walkState, nodes.size() - 1);

            current = next;
            currentShape = nextShape;
            travel = exit;
        }

        if (loop && points.size() > 1) {
            points.add(points.get(loopStartIndex));
        }

        scanMarks(walk, nodes, marks, bumpMarks);
        RailPath path = new RailPath(key, points, nodes, loop, loopStartIndex);
        for (int index : junctionIndices) {
            if (index < path.cumulative.length) {
                BlockPos rail = nodes.get(index);
                BlockPos station = walk.boxLoaded(rail, JUNCTION_STATION_RADIUS)
                    ? BlockEntityScan.nearest(world, rail, JUNCTION_STATION_RADIUS, StationBlockEntity.class)
                    : null;
                path.junctions.add(new PathJunction(path.cumulative[index], rail, station));
            }
        }
        path.resolveMarks(marks, bumpMarks);
        MmsMetro.LOGGER.debug("[path] built {} nodes, length {}, loop {}", nodes.size(), path.length, loop);
        return path;
    }

    private static boolean hasJunction(Walk walk, BlockPos node) {
        return walk.loaded(node) && walk.world.getBlockEntity(node.down()) instanceof JunctionBlockEntity;
    }

    // Node-index gap beyond which two hits on one marker count as separate passes of the track.
    private static final int PASS_GAP = 8;

    // Finds every marker near the track and binds one stop per pass of the track: the nearest node within each run of nearby indices.
    private static void scanMarks(Walk walk, List<BlockPos> nodes,
                                  List<StationMark> outStations, List<BumpMark> outBumps) {
        World world = walk.world;
        int r = BUMP_H_RADIUS;
        Map<BlockPos, List<Integer>> nodeIndices = new HashMap<>();
        LongSet scanChunks = new LongOpenHashSet();
        for (int i = 0; i < nodes.size(); i++) {
            BlockPos rail = nodes.get(i);
            nodeIndices.computeIfAbsent(rail, k -> new ArrayList<>()).add(i);
            addChunks(scanChunks, rail, r);
        }

        List<MarkerHits> stationHits = new ArrayList<>();
        List<MarkerHits> bumpHits = new ArrayList<>();
        LongIterator it = scanChunks.iterator();
        while (it.hasNext()) {
            long chunk = it.nextLong();
            int cx = ChunkPos.getPackedX(chunk);
            int cz = ChunkPos.getPackedZ(chunk);
            if (!walk.chunkLoaded(cx, cz)) {
                continue;
            }
            BlockEntityScan.forEachInChunk(world, cx, cz, StationBlockEntity.class,
                be -> collectHits(be.getPos(), nodes, nodeIndices, STATION_H_RADIUS, stationHits));
            BlockEntityScan.forEachInChunk(world, cx, cz, SpeedBumpBlockEntity.class,
                be -> collectHits(be.getPos(), nodes, nodeIndices, r, bumpHits));
        }
        stationHits.sort(MarkerHits.SCAN_ORDER);
        bumpHits.sort(MarkerHits.SCAN_ORDER);

        for (MarkerHits marker : stationHits) {
            for (int nodeIndex : nearestPerPass(marker.hits)) {
                outStations.add(readStationMark(walk, marker.pos, nodeIndex));
            }
        }
        for (MarkerHits marker : bumpHits) {
            String direction = "";
            String stationKey = "";
            if (world.getBlockEntity(marker.pos) instanceof SpeedBumpBlockEntity bump) {
                direction = bump.getDirection();
                stationKey = bump.getStationKey();
            }
            double nearestRailSq = walk.boxLoaded(marker.pos, r)
                ? nearestRailSq(world, marker.pos, r)
                : Double.MAX_VALUE;
            marker.hits.removeIf(hit -> hit[1] > nearestRailSq + BUMP_TRACK_SLACK_SQ);
            if (marker.hits.isEmpty()) {
                continue;
            }
            for (int nodeIndex : nearestPerPass(marker.hits)) {
                outBumps.add(new BumpMark(nodeIndex, direction, stationKey));
            }
        }
    }

    // Squared distance from a marker to the closest rail block of any track in its reach.
    private static double nearestRailSq(World world, BlockPos marker, int hRadius) {
        double best = Double.MAX_VALUE;
        BlockPos.Mutable rail = new BlockPos.Mutable();
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -STATION_UP; dy <= STATION_DOWN; dy++) {
                    rail.set(marker.getX() + dx, marker.getY() + dy, marker.getZ() + dz);
                    if (world.getBlockState(rail).getBlock() instanceof AbstractRailBlock) {
                        best = Math.min(best, marker.getSquaredDistance(rail));
                    }
                }
            }
        }
        return best;
    }

    // Every node a marker sits within reach of, as {node index, squared distance} pairs, added to out when there is at least one.
    private static void collectHits(BlockPos marker, List<BlockPos> nodes, Map<BlockPos, List<Integer>> nodeIndices,
                                    int hRadius, List<MarkerHits> out) {
        List<double[]> hits = new ArrayList<>();
        int firstNode = Integer.MAX_VALUE;
        BlockPos.Mutable rail = new BlockPos.Mutable();
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -STATION_UP; dy <= STATION_DOWN; dy++) {
                    List<Integer> indices = nodeIndices.get(rail.set(marker.getX() + dx, marker.getY() + dy, marker.getZ() + dz));
                    if (indices == null) {
                        continue;
                    }
                    for (int i : indices) {
                        hits.add(new double[]{i, marker.getSquaredDistance(nodes.get(i))});
                        firstNode = Math.min(firstNode, i);
                    }
                }
            }
        }
        if (!hits.isEmpty()) {
            out.add(new MarkerHits(marker.toImmutable(), firstNode, hits));
        }
    }

    // One marker's hits along the path, with the lowest node index that reaches it.
    private record MarkerHits(BlockPos pos, int firstNode, List<double[]> hits) {

        private static final Comparator<MarkerHits> SCAN_ORDER = (a, b) -> a.firstNode != b.firstNode
            ? Integer.compare(a.firstNode, b.firstNode)
            : BlockEntityScan.compareZyx(a.pos, b.pos);
    }

    // Splits one marker's hits into passes and returns the nearest node of each.
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

    // Reads a station block's settings and nearest speaker into a mark bound to its nearest node.
    private static StationMark readStationMark(Walk walk, BlockPos best, int nodeIndex) {
        World world = walk.world;
        int dwell = 100;
        boolean terminus = false;
        String name = "";
        String line = "";
        String direction = "";
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
            nextStation = station.getNextStation();
            exitDirection = station.getExitDirection();
            hub = station.isHub();
            transferLine = station.getTransferLine();
            lineColor = station.getLineColor();
        }
        BlockPos speaker = walk.boxLoaded(best, SPEAKER_SEARCH_RADIUS)
            ? BlockEntityScan.nearest(world, best, SPEAKER_SEARCH_RADIUS, SpeakerBlockEntity.class)
            : null;
        return new StationMark(nodeIndex, dwell, best, terminus,
            name, line, direction, nextStation, exitDirection, hub, transferLine, lineColor, speaker);
    }

    // A station marker may sit one block out horizontally and two below to two above a rail node.
    private static final int STATION_H_RADIUS = 1;
    private static final int STATION_UP = 2;
    private static final int STATION_DOWN = 2;

    // A speed bump reaches four blocks out to either side of the track.
    private static final int BUMP_H_RADIUS = 4;

    // Extra squared distance beyond the closest rail at which a node may still claim a bump.
    private static final double BUMP_TRACK_SLACK_SQ = 2.0;

    // A walk-state identity: a node together with the heading the walk departs it on.
    private record WalkState(BlockPos pos, Direction heading) {
    }

    // The exit a junction block under this node dictates for a train arriving on the given heading, or null when it leaves that heading unrouted.
    private static Direction junctionExit(Walk walk, BlockPos node, Direction travel) {
        BlockEntity be = walk.world.getBlockEntity(node.down());
        if (be instanceof JunctionBlockEntity junction) {
            return junction.getExit(travel);
        }
        return null;
    }

    private static RailShape railShape(Walk walk, BlockPos pos) {
        return walk.loaded(pos) ? railShape(walk.world, pos) : null;
    }

    private static RailShape railShape(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof AbstractRailBlock rail) {
            return state.get(rail.getShapeProperty());
        }
        return null;
    }

    private static Vec3d centerPoint(BlockPos pos, boolean ascending) {
        double y = pos.getY() + (ascending ? 0.5 : 0.0625);
        return new Vec3d(pos.getX() + 0.5, y, pos.getZ() + 0.5);
    }

    private static BlockPos step(Walk walk, BlockPos current, RailShape shape, Direction travel) {
        BlockPos horizontal = current.offset(travel);
        BlockPos[] candidates = travel == ascendingUp(shape)
            ? new BlockPos[]{horizontal.up(), horizontal, horizontal.down()}
            : new BlockPos[]{horizontal, horizontal.down(), horizontal.up()};

        for (BlockPos candidate : candidates) {
            if (walk.loaded(candidate) && AbstractRailBlock.isRail(walk.world, candidate)) {
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
