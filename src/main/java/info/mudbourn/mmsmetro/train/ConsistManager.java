package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.item.MetroSpawnerItem;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModEntities;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Owns the live consists per world, ticks them, and spawns or removes trains.
public final class ConsistManager {

    private static final Map<ServerWorld, List<Consist>> BY_WORLD = new HashMap<>();

    // How often, in ticks, we rebuild a consist for loaded cars with no live one, the path back to motion after a restart or chunk reload.
    private static final int RECONCILE_INTERVAL = 20;

    private static final Map<ServerWorld, Integer> RECONCILE_TIMERS = new HashMap<>();

    // The force-loaded chunks per world, with the car chunks and radius they were expanded from, so an unchanged train skips the rebuild.
    private static final Map<ServerWorld, ForcedChunks> FORCED_CHUNKS = new HashMap<>();

    private record ForcedChunks(int radius, LongSet carChunks, LongSet forced) {
    }

    // Every loaded metro car in the world being ticked, gathered once so each consist's headway check shares one entity scan.
    private static List<? extends MetroCarEntity> tickCars;

    private static ServerWorld tickCarsWorld;

    public static void init() {
        ServerTickEvents.END_WORLD_TICK.register(ConsistManager::tickWorld);
        AttackEntityCallback.EVENT.register(ConsistManager::onAttack);
    }

    // Left-clicking a car with the spawner kills its whole train, swing cancelled.
    private static ActionResult onAttack(net.minecraft.entity.player.PlayerEntity player,
                                         World world, net.minecraft.util.Hand hand,
                                         net.minecraft.entity.Entity entity,
                                         net.minecraft.util.hit.EntityHitResult hit) {
        if (!(entity instanceof MetroCarEntity car)
            || !(player.getStackInHand(hand).getItem() instanceof MetroSpawnerItem)) {
            return ActionResult.PASS;
        }

        if (world instanceof ServerWorld serverWorld) {
            int cars = removeConsist(serverWorld, car.getConsistId());
            String message = cars > 0
                ? "Removed " + cars + " car(s)."
                : "No cars removed; the rest of this train may be in an unloaded chunk.";
            player.sendMessage(net.minecraft.text.Text.literal(message), true);
        }
        return ActionResult.SUCCESS;
    }

    // Every loaded metro car in the world, whether or not it is still tracked in the in-memory registry (which is empty after a reload).
    private static List<? extends MetroCarEntity> allCars(ServerWorld world) {
        return world.getEntitiesByType(ModEntities.METRO_CAR, car -> true);
    }

    // The loaded cars of the world, the shared per-tick list while that world ticks its consists, else a fresh scan.
    static List<? extends MetroCarEntity> carsThisTick(ServerWorld world) {
        return tickCarsWorld == world && tickCars != null ? tickCars : allCars(world);
    }

    // Discards every car sharing a consist id and drops that consist from the live registry, returning the number of cars removed.
    private static int removeConsist(ServerWorld world, java.util.UUID consistId) {
        int removed = 0;
        for (MetroCarEntity car : allCars(world)) {
            if (car.getConsistId().equals(consistId)) {
                car.discard();
                removed++;
            }
        }
        forgetConsist(world, consistId);
        return removed;
    }

    // Drops any tracked consist whose lead car matches the id so it stops ticking.
    private static void forgetConsist(ServerWorld world, java.util.UUID consistId) {
        List<Consist> list = BY_WORLD.get(world);
        if (list == null) {
            return;
        }
        list.removeIf(consist -> !consist.cars().isEmpty()
            && consist.cars().get(0).getConsistId().equals(consistId));
    }

    // The live consists ticking in a world, so one consist can reason about the others at a shared junction.
    public static List<Consist> liveConsists(ServerWorld world) {
        List<Consist> list = BY_WORLD.get(world);
        return list != null ? list : List.of();
    }

    public static Consist spawn(ServerWorld world, BlockPos rail, Direction facing, int cars, MetroConfig config) {
        RailPath path = buildLinePath(world, rail, facing);
        if (path == null) {
            return null;
        }
        double initialHead = Math.min((cars - 1) * config.carSpacing, path.length());
        return spawnConsistOnPath(world, path, initialHead, cars, config);
    }

    // Walks the line from a rail, heading the way the player faces so a directional line departs as set up and only reversing when that faces an immediate dead end; null when neither heading yields a path.
    private static RailPath buildLinePath(ServerWorld world, BlockPos rail, Direction facing) {
        RailPath path = RailPath.build(world, rail, facing, RailPath.MAX_NODES);
        if (path.length() <= 0.0) {
            path = RailPath.build(world, rail, facing.getOpposite(), RailPath.MAX_NODES);
        }
        return path.length() > 0.0 ? path : null;
    }

    // Creates a train of cars on a resolved path with its lead at headArc, spawns the entities, and tracks the consist.
    private static Consist spawnConsistOnPath(ServerWorld world, RailPath path, double headArc, int cars, MetroConfig config) {
        Consist consist = new Consist(path, config, headArc);
        java.util.UUID consistId = java.util.UUID.randomUUID();
        for (int i = 0; i < cars; i++) {
            MetroCarEntity car = new MetroCarEntity(ModEntities.METRO_CAR, world);
            car.setCarIndex(i);
            car.setConsistId(consistId);
            consist.addCar(car);
        }

        consist.placeCars();
        for (MetroCarEntity car : consist.cars()) {
            world.spawnEntity(car);
        }

        BY_WORLD.computeIfAbsent(world, w -> new ArrayList<>()).add(consist);
        return consist;
    }

    // Strips a line's display name to its id: every character that is not a Roman letter or digit removed, so "Line 1: Fort Kelvin" becomes "Line1FortKelvin".
    public static String lineId(String lineName) {
        return lineName.replaceAll("[^A-Za-z0-9]", "");
    }

    // Number of interleaved waves a circulate lays down, alternating the terminal they launch from so both directions are served and each direction's wait is halved.
    private static final int WAVE_COUNT = 4;

    // One wave of the circulation plan: a canonical path to seed from and the stop arcs to drop trains at, plus the arc its own terminal train must reach before the next wave launches.
    private record Wave(BlockPos origin, Direction dir, List<Double> arcs, double triggerArc) {
    }

    // A wave still to launch, held until an earlier wave's terminal train reaches its trigger arc so the waves stay staggered.
    private record PendingWave(ServerWorld world, MetroCarEntity trigger, double triggerArc,
                               List<Wave> remaining, int cars, MetroConfig config) {
    }

    private static final List<PendingWave> PENDING_WAVES = new ArrayList<>();

    // Lays out WAVE_COUNT interleaved waves and launches the first, queuing the rest to fire in turn as each preceding wave pulls clear; returns the number of trains in the first wave.
    public static int circulate(ServerWorld world, String lineId, int cars, MetroConfig config) {
        List<Wave> plan = planWaves(world, lineId);
        if (plan.isEmpty()) {
            return 0;
        }

        int spawned = spawnWave(world, plan.get(0), cars, config);
        if (spawned <= 0) {
            return 0;
        }
        queueRemaining(world, firstWaveLead, plan, 0, cars, config);
        return spawned;
    }

    // Builds the alternating-terminal wave plan: even waves run inward from the start terminal, odd waves inward from the opposite one, each dropping trains at every other of its own stops.
    private static List<Wave> planWaves(ServerWorld world, String lineId) {
        List<BlockPos> stations = info.mudbourn.mmsmetro.station.StationIndex.stationsOnLine(world, lineId);
        if (stations.isEmpty()) {
            return List.of();
        }

        BlockPos[] terminals = terminals(stations);
        Wave forward = buildWave(world, terminals[0], lineId);
        if (forward == null) {
            return List.of();
        }
        Wave reverse = buildWave(world, terminals[1], lineId);

        List<Wave> plan = new ArrayList<>();
        for (int i = 0; i < WAVE_COUNT; i++) {
            Wave wave = (i % 2 == 1 && reverse != null) ? reverse : forward;
            plan.add(wave);
        }
        return plan;
    }

    // Resolves the wave launched inward from one terminal: its canonical path, the every-other-stop arcs, and the nearest stop's arc as the trigger for the following wave.
    private static Wave buildWave(ServerWorld world, BlockPos terminal, String lineId) {
        BlockPos rail = railNearStation(world, terminal);
        if (rail == null) {
            return null;
        }
        RailPath path = buildFromTerminal(world, rail);
        if (path == null) {
            return null;
        }

        List<PathStation> stops = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (PathStation station : path.stations()) {
            if (lineId(station.line()).equalsIgnoreCase(lineId) && seen.add(station.pos())) {
                stops.add(station);
            }
        }
        if (stops.isEmpty()) {
            return null;
        }
        double triggerArc = stops.get(stops.size() >= 2 ? 1 : 0).arc();
        return new Wave(path.origin(), path.initialDir(), everyOtherArc(stops), triggerArc);
    }

    // Queues the waves after index `from`, each waiting on the terminal train of the wave before it; a null lead or an exhausted plan queues nothing.
    private static void queueRemaining(ServerWorld world, MetroCarEntity lead, List<Wave> plan,
                                       int from, int cars, MetroConfig config) {
        if (lead == null || from + 1 >= plan.size()) {
            return;
        }
        PENDING_WAVES.add(new PendingWave(world, lead, plan.get(from).triggerArc(),
            new ArrayList<>(plan.subList(from + 1, plan.size())), cars, config));
    }

    // The two ends of a line: the start terminal (southernmost on a north-south line, westernmost on an east-west one, by whichever axis the stops span more) and the opposite terminal.
    private static BlockPos[] terminals(List<BlockPos> stations) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : stations) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        boolean northSouth = (maxZ - minZ) >= (maxX - minX);
        BlockPos start = stations.get(0);
        BlockPos opposite = stations.get(0);
        for (BlockPos pos : stations) {
            if (northSouth ? pos.getZ() > start.getZ() : pos.getX() < start.getX()) {
                start = pos;
            }
            if (northSouth ? pos.getZ() < opposite.getZ() : pos.getX() > opposite.getX()) {
                opposite = pos;
            }
        }
        return new BlockPos[]{start, opposite};
    }

    // The rail serving a station block, searched in the small neighborhood the path walker binds stations within.
    private static BlockPos railNearStation(ServerWorld world, BlockPos station) {
        for (int dy = 0; dy >= -2; dy--) {
            BlockPos rail = scanRailLayer(world, station, dy);
            if (rail != null) {
                return rail;
            }
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos rail = scanRailLayer(world, station, dy);
            if (rail != null) {
                return rail;
            }
        }
        return null;
    }

    private static BlockPos scanRailLayer(ServerWorld world, BlockPos station, int dy) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos candidate = station.add(dx, dy, dz);
                if (AbstractRailBlock.isRail(world, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // Walks the whole line from a terminal rail: the longer of the paths its two rail exits open, so the walk heads into the line rather than off its dead end.
    private static RailPath buildFromTerminal(ServerWorld world, BlockPos terminalRail) {
        RailPath best = null;
        for (Direction exit : RailPath.railExits(world, terminalRail)) {
            RailPath path = RailPath.build(world, terminalRail, exit, RailPath.MAX_NODES);
            if (best == null || path.length() > best.length()) {
                best = path;
            }
        }
        return best != null && best.length() > 0.0 ? best : null;
    }

    // True when a metro car already sits within a car spacing of any point a new train would occupy along the path, so a wave never spawns on top of a standing train.
    private static boolean arcOccupied(ServerWorld world, RailPath path, double headArc, int cars, MetroConfig config) {
        double clearance = config.carSpacing;
        for (int i = 0; i < cars; i++) {
            double arc = Math.max(0.0, headArc - i * config.carSpacing);
            Vec3d p = path.sample(arc).pos();
            net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                p.x - clearance, p.y - clearance, p.z - clearance,
                p.x + clearance, p.y + clearance, p.z + clearance);
            if (!world.getEntitiesByType(ModEntities.METRO_CAR, box, car -> !car.isRemoved()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Arc positions of every other stop from the start terminal, the stations a single wave fills.
    private static List<Double> everyOtherArc(List<PathStation> stops) {
        List<Double> arcs = new ArrayList<>();
        for (int i = 0; i < stops.size(); i += 2) {
            arcs.add(stops.get(i).arc());
        }
        return arcs;
    }

    // Lead car of the terminal train (the one seeded at the first stop) of the wave just spawned, so the following wave can wait on it; null when no such train was placed.
    private static MetroCarEntity firstWaveLead;

    // Spawns a train at each of the wave's stop arcs on a fresh copy of its canonical path, remembering the terminal train's lead for staggering the next wave.
    private static int spawnWave(ServerWorld world, Wave wave, int cars, MetroConfig config) {
        firstWaveLead = null;
        int spawned = 0;
        for (int i = 0; i < wave.arcs().size(); i++) {
            RailPath path = RailPath.build(world, wave.origin(), wave.dir(), RailPath.MAX_NODES);
            if (path.length() <= 0.0) {
                continue;
            }
            double headArc = Math.min(wave.arcs().get(i), path.length());
            // A single-track terminal (Spanish-solution) holds one train, so never drop a train onto a stop another already occupies.
            if (arcOccupied(world, path, headArc, cars, config)) {
                continue;
            }
            Consist consist = spawnConsistOnPath(world, path, headArc, cars, config);
            if (consist != null) {
                spawned++;
                if (i == 0 && !consist.cars().isEmpty()) {
                    firstWaveLead = consist.cars().get(0);
                }
            }
        }
        return spawned;
    }

    // Fires any queued wave whose preceding wave's terminal train has reached its trigger arc, chaining the wave after it, and drops queues whose trigger train is gone.
    private static void tickPendingWaves(ServerWorld world) {
        List<PendingWave> chained = new ArrayList<>();
        Iterator<PendingWave> it = PENDING_WAVES.iterator();
        while (it.hasNext()) {
            PendingWave pending = it.next();
            if (pending.world != world) {
                continue;
            }
            if (pending.trigger.isRemoved()) {
                it.remove();
                continue;
            }
            if (pending.trigger.getArcLength() + 1.0e-3 >= pending.triggerArc) {
                spawnWave(world, pending.remaining.get(0), pending.cars, pending.config);
                if (firstWaveLead != null && pending.remaining.size() > 1) {
                    chained.add(new PendingWave(world, firstWaveLead, pending.remaining.get(0).triggerArc(),
                        new ArrayList<>(pending.remaining.subList(1, pending.remaining.size())),
                        pending.cars, pending.config));
                }
                it.remove();
            }
        }
        PENDING_WAVES.addAll(chained);
    }

    // Discards every live train serving the line, returning how many trains were removed.
    public static int removeByLine(ServerWorld world, String lineId) {
        List<Consist> list = BY_WORLD.get(world);
        if (list == null) {
            return 0;
        }
        List<java.util.UUID> ids = new ArrayList<>();
        for (Consist consist : list) {
            if (!consist.cars().isEmpty() && lineId(consist.lineName()).equalsIgnoreCase(lineId)) {
                ids.add(consist.cars().get(0).getConsistId());
            }
        }
        for (java.util.UUID id : ids) {
            removeConsist(world, id);
        }
        return ids.size();
    }

    // Line ids of the trains currently running, for the remove command's autocomplete.
    public static java.util.Set<String> activeLineIds(ServerWorld world) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        List<Consist> list = BY_WORLD.get(world);
        if (list != null) {
            for (Consist consist : list) {
                String id = lineId(consist.lineName());
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    public static BlockPos findRail(World world, BlockPos base) {
        BlockPos[] candidates = {base, base.down(), base.down(2)};
        for (BlockPos candidate : candidates) {
            if (AbstractRailBlock.isRail(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // Number of distinct trains (consists) removed.
    public static int removeAll(ServerWorld world) {
        java.util.Set<java.util.UUID> trains = new java.util.HashSet<>();
        for (MetroCarEntity car : allCars(world)) {
            trains.add(car.getConsistId());
            car.discard();
        }
        BY_WORLD.remove(world);
        return trains.size();
    }

    public static boolean removeNearest(ServerWorld world, Vec3d pos) {
        MetroCarEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (MetroCarEntity car : allCars(world)) {
            double distance = car.squaredDistanceTo(pos);
            if (distance < best) {
                best = distance;
                nearest = car;
            }
        }

        if (nearest == null) {
            return false;
        }

        removeConsist(world, nearest.getConsistId());
        return true;
    }

    private static void tickWorld(ServerWorld world) {
        int timer = RECONCILE_TIMERS.getOrDefault(world, 0) - 1;
        if (timer <= 0) {
            timer = RECONCILE_INTERVAL;
            reconcile(world);
        }
        RECONCILE_TIMERS.put(world, timer);

        tickPendingWaves(world);

        List<Consist> list = BY_WORLD.get(world);
        if (list != null && !list.isEmpty()) {
            tickCars = allCars(world);
            tickCarsWorld = world;
            try {
                Iterator<Consist> it = list.iterator();
                while (it.hasNext()) {
                    Consist consist = it.next();
                    if (consist.isFinished()) {
                        it.remove();
                    } else {
                        consist.tick();
                    }
                }
            } finally {
                tickCars = null;
                tickCarsWorld = null;
            }
        }

        updateForcedChunks(world, list);
    }

    // Keeps the chunks around every live train force-loaded so a moving consist and the rail ahead never fall into an unloaded chunk, which would strand cars and truncate the walked path; the desired set is rebuilt only when a car changes chunk and diffed against the currently forced set.
    private static void updateForcedChunks(ServerWorld world, List<Consist> list) {
        LongSet carChunks = new LongOpenHashSet();
        if (list != null) {
            for (Consist consist : list) {
                for (MetroCarEntity car : consist.cars()) {
                    if (!car.isRemoved()) {
                        carChunks.add(ChunkPos.toLong(car.getBlockPos()));
                    }
                }
            }
        }

        int radius = MmsMetro.config().chunkRadius;
        ForcedChunks previous = FORCED_CHUNKS.get(world);
        if (previous == null ? carChunks.isEmpty() : previous.radius() == radius && previous.carChunks().equals(carChunks)) {
            return;
        }

        LongSet desired = new LongOpenHashSet();
        for (long carChunk : carChunks) {
            int cx = ChunkPos.getPackedX(carChunk);
            int cz = ChunkPos.getPackedZ(carChunk);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    desired.add(ChunkPos.toLong(cx + dx, cz + dz));
                }
            }
        }

        LongSet current = previous != null ? previous.forced() : new LongOpenHashSet();
        for (long key : desired) {
            if (!current.contains(key)) {
                world.setChunkForced(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), true);
            }
        }
        for (long key : current) {
            if (!desired.contains(key)) {
                world.setChunkForced(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key), false);
            }
        }

        if (desired.isEmpty()) {
            FORCED_CHUNKS.remove(world);
        } else {
            FORCED_CHUNKS.put(world, new ForcedChunks(radius, carChunks, desired));
        }
    }

    // Rebuilds consists for any loaded cars with no live consist by grouping the loaded cars by consist id and reconstructing a consist for any group not already tracked by the same car objects (reloaded cars are new objects, so this refires).
    private static void reconcile(ServerWorld world) {
        Map<java.util.UUID, List<MetroCarEntity>> groups = new HashMap<>();
        for (MetroCarEntity car : allCars(world)) {
            if (!car.isRemoved()) {
                groups.computeIfAbsent(car.getConsistId(), id -> new ArrayList<>()).add(car);
            }
        }
        if (groups.isEmpty()) {
            return;
        }

        List<Consist> list = BY_WORLD.computeIfAbsent(world, w -> new ArrayList<>());
        for (Map.Entry<java.util.UUID, List<MetroCarEntity>> entry : groups.entrySet()) {
            List<MetroCarEntity> cars = entry.getValue();
            Consist tracked = trackedConsist(list, entry.getKey());
            if (tracked != null && sameCars(tracked, cars)) {
                continue;
            }
            if (tracked != null) {
                list.remove(tracked);
            }
            Consist rebuilt = reconstruct(world, cars);
            if (rebuilt != null) {
                list.add(rebuilt);
            }
        }
    }

    // The tracked consist whose lead car carries this id, or null.
    private static Consist trackedConsist(List<Consist> list, java.util.UUID consistId) {
        for (Consist consist : list) {
            if (!consist.cars().isEmpty()
                && consist.cars().get(0).getConsistId().equals(consistId)) {
                return consist;
            }
        }
        return null;
    }

    // True when the consist is driving exactly these car objects by identity, so a reloaded set of cars (new objects) counts as different.
    private static boolean sameCars(Consist consist, List<MetroCarEntity> cars) {
        return consist.cars().size() == cars.size() && consist.cars().containsAll(cars);
    }

    // Rebuilds one consist from its loaded cars, resolving a fresh path from the tail's rail in the direction the train faces so followers sit behind the lead exactly as they do at spawn.
    private static Consist reconstruct(ServerWorld world, List<MetroCarEntity> cars) {
        cars.sort(java.util.Comparator.comparingInt(MetroCarEntity::getCarIndex));
        MetroCarEntity lead = cars.get(0);
        MetroCarEntity tail = cars.get(cars.size() - 1);
        MetroConfig config = MmsMetro.config();

        // Preferred: rebuild the exact path the consist last ran, from the origin and heading persisted on the lead, and seat the lead at its own saved arc so every car lands back on the same track even across a junction.
        BlockPos origin = lead.getPathOrigin();
        Direction initialDir = lead.getPathInitialDir();
        if (origin != null && initialDir != null) {
            RailPath path = RailPath.build(world, origin, initialDir, RailPath.MAX_NODES);
            if (path.length() > 0.0) {
                return assemble(config, path, Math.min(lead.getArcLength(), path.length()), cars);
            }
        }

        // Fallback for cars saved before the identity was stored, or an origin whose rail is gone: walk a fresh path from the tail as before.
        BlockPos rail = findRail(world, tail.getBlockPos());
        if (rail == null) {
            rail = findRail(world, lead.getBlockPos());
        }
        if (rail == null) {
            return null;
        }

        RailPath path = RailPath.build(world, rail, headingOf(lead, tail), RailPath.MAX_NODES);
        if (path.length() <= 0.0) {
            return null;
        }
        return assemble(config, path, Math.min((cars.size() - 1) * config.carSpacing, path.length()), cars);
    }

    // Builds a consist on a resolved path with the given head arc and seats its cars.
    private static Consist assemble(MetroConfig config, RailPath path, double headArc, List<MetroCarEntity> cars) {
        Consist consist = new Consist(path, config, headArc);
        for (MetroCarEntity car : cars) {
            consist.addCar(car);
        }
        consist.placeCars();
        return consist;
    }

    // The travel direction of a train: the tail-to-lead vector for a multi-car train, or the lead's stored heading for a single car.
    private static Direction headingOf(MetroCarEntity lead, MetroCarEntity tail) {
        if (lead != tail) {
            double dx = lead.getX() - tail.getX();
            double dz = lead.getZ() - tail.getZ();
            if (Math.abs(dx) > 1.0e-4 || Math.abs(dz) > 1.0e-4) {
                return Math.abs(dx) > Math.abs(dz)
                    ? (dx > 0 ? Direction.EAST : Direction.WEST)
                    : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            }
        }
        return switch (Math.floorMod(Math.round(lead.getPathYaw() / 90.0f), 4)) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }
}
