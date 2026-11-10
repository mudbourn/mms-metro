package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.item.MetroSpawnerItem;
import info.mudbourn.mmsmetro.path.PathKey;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModEntities;
import info.mudbourn.mmsmetro.station.StationIndex;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Ticks the saved trains of each world and spawns, lists and removes them.
public final class ConsistManager {

    // Running circulations, ticked with their world.
    private static final List<Circulation> CIRCULATIONS = new ArrayList<>();

    // Ticks to wait after a teleport or a circulate spawn before redrawing, so the client has the chunks.
    static final int REDRAW_DELAY = 20;

    // Blocks a player must jump in one tick for the move to count as a teleport.
    private static final double TELEPORT_DISTANCE = 16.0;

    // Where each player was last tick, to spot teleports.
    private record Sighting(ServerWorld world, Vec3d pos) {
    }

    private static final Map<UUID, Sighting> LAST_SEEN = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_WORLD_TICK.register(ConsistManager::tickWorld);
        ServerTickEvents.END_SERVER_TICK.register(ConsistManager::watchTeleports);
        AttackEntityCallback.EVENT.register(ConsistManager::onAttack);
        ServerEntityEvents.ENTITY_LOAD.register(LegacyCars::onLoad);
        ServerLifecycleEvents.SERVER_STOPPING.register(ConsistManager::despawnAllViews);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LegacyCars.clear();
            LAST_SEEN.clear();
            CIRCULATIONS.clear();
            TrainTickets.clear();
        });
    }

    // Left-clicking a car with the spawner removes its whole train, swing cancelled.
    private static ActionResult onAttack(PlayerEntity player, World world, Hand hand,
                                         Entity entity, EntityHitResult hit) {
        if (!(entity instanceof MetroCarEntity car)
            || !(player.getStackInHand(hand).getItem() instanceof MetroSpawnerItem)) {
            return ActionResult.PASS;
        }

        if (world instanceof ServerWorld serverWorld) {
            boolean removed = MetroState.of(serverWorld).removeTrain(car.getConsistId());
            car.discard();
            player.sendMessage(Text.literal(removed ? "Removed the train." : "Removed the car."), true);
        }
        return ActionResult.SUCCESS;
    }

    // The saved trains of a world.
    public static Collection<Consist> trains(ServerWorld world) {
        return MetroState.of(world).trains();
    }

    public static Consist spawn(ServerWorld world, BlockPos rail, Direction facing, int cars, MetroConfig config) {
        MetroState state = MetroState.of(world);
        RailPath path = state.pathFor(new PathKey(rail.toImmutable(), facing));
        if (path == null) {
            path = state.pathFor(new PathKey(rail.toImmutable(), facing.getOpposite()));
        }
        if (path == null) {
            return null;
        }
        double initialHead = Math.min((cars - 1) * config.carSpacing, path.length());
        return createTrain(state, path, initialHead, cars, config);
    }

    // Creates a train on a baked path with its lead at headArc and seats its views.
    static Consist createTrain(MetroState state, RailPath path, double headArc, int cars, MetroConfig config) {
        Consist consist = new Consist(UUID.randomUUID(), config, path, headArc, cars);
        state.addTrain(consist);
        consist.syncViews();
        return consist;
    }

    // Strips a line's display name to its id: every character that is not a Roman letter or digit removed.
    public static String lineId(String lineName) {
        return lineName.replaceAll("[^A-Za-z0-9]", "");
    }

    // Starts circulating a line from its terminal stations, one wave per terminal at a time, reporting progress to the source; returns the trains it will place.
    public static int circulate(ServerCommandSource source, String lineId, int cars, MetroConfig config) {
        ServerWorld world = source.getWorld();
        List<BlockPos> stations = StationIndex.stationsOnLine(world, lineId);
        if (stations.isEmpty()) {
            return 0;
        }
        MetroState state = MetroState.of(world);
        String lineName = lineId;
        List<Circulation.Launcher> launchers = new ArrayList<>();
        for (BlockPos terminal : terminalStations(world, stations)) {
            if (!(world.getBlockEntity(terminal) instanceof StationBlockEntity station)) {
                continue;
            }
            Circulation.Launcher launcher = launcherAt(state, world, terminal, station, lineId, cars, config);
            if (launcher != null) {
                launchers.add(launcher);
                if (!station.getLineName().isEmpty()) {
                    lineName = station.getLineName();
                }
            }
        }
        if (launchers.isEmpty()) {
            return 0;
        }
        Circulation circulation = new Circulation(world, lineName, source, launchers, cars);
        CIRCULATIONS.add(circulation);
        circulation.tick(state, config);
        return circulation.total();
    }

    // The line's stations marked terminus, or its two farthest-apart stations when none is marked.
    private static List<BlockPos> terminalStations(ServerWorld world, List<BlockPos> stations) {
        List<BlockPos> marked = new ArrayList<>();
        for (BlockPos pos : stations) {
            if (world.getBlockEntity(pos) instanceof StationBlockEntity station && station.isTerminus()) {
                marked.add(pos);
            }
        }
        if (!marked.isEmpty()) {
            return marked;
        }
        BlockPos[] ends = terminals(stations);
        return ends[0].equals(ends[1]) ? List.of(ends[0]) : List.of(ends[0], ends[1]);
    }

    // A terminal's launcher: the path toward its Next Station, trains starting with the tail at the terminal, one train for every other stop on the path.
    private static Circulation.Launcher launcherAt(MetroState state, ServerWorld world, BlockPos terminal,
            StationBlockEntity station, String lineId, int cars, MetroConfig config) {
        BlockPos rail = railNearStation(world, terminal);
        if (rail == null) {
            return null;
        }
        RailPath built = buildFromTerminal(world, terminal, rail);
        if (built == null) {
            return null;
        }
        RailPath path = state.pathFor(built.key());
        if (path == null) {
            return null;
        }
        double headArc = Math.min((cars - 1) * config.carSpacing, path.length());
        double firstStop = Circulation.firstStopArc(path, headArc, station.getStationName());
        if (Double.isNaN(firstStop)) {
            return null;
        }
        Set<BlockPos> stops = new HashSet<>();
        for (PathStation stop : path.stations()) {
            if (lineId(stop.line()).equalsIgnoreCase(lineId)) {
                stops.add(stop.pos());
            }
        }
        return new Circulation.Launcher(path, headArc, firstStop, Math.max(1, (stops.size() + 1) / 2));
    }

    // The two ends of a line: the start terminal and the opposite terminal.
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

    // Walks the line from a terminal rail: the longest path whose first other stop is the terminal's Next Station, else the longest path.
    private static RailPath buildFromTerminal(ServerWorld world, BlockPos terminal, BlockPos terminalRail) {
        StationBlockEntity station = world.getBlockEntity(terminal) instanceof StationBlockEntity be ? be : null;
        RailPath best = null;
        boolean bestHeaded = false;
        for (Direction exit : RailPath.railExits(world, terminalRail)) {
            RailPath path = RailPath.build(world, terminalRail, exit, RailPath.MAX_NODES);
            boolean headed = station != null && headsToNext(path, station);
            if (best == null || (headed && !bestHeaded) || (headed == bestHeaded && path.length() > best.length())) {
                best = path;
                bestHeaded = headed;
            }
        }
        return best != null && best.length() > 0.0 ? best : null;
    }

    // True when the first stop past the terminal's own station is the one the terminal names as its Next Station.
    private static boolean headsToNext(RailPath path, StationBlockEntity terminal) {
        String own = stationKey(terminal.getStationName());
        String next = stationKey(terminal.getNextStation());
        if (next.isEmpty()) {
            return false;
        }
        for (PathStation stop : path.stations()) {
            String name = stationKey(stop.name());
            if (!name.equals(own)) {
                return name.startsWith(next);
            }
        }
        return false;
    }

    // A station name lowercased with every character that is not a Roman letter or digit removed.
    private static String stationKey(String name) {
        return lineId(name).toLowerCase(Locale.ROOT);
    }

    // True when a train already sits within a car spacing of any point a new train would occupy along the path.
    static boolean arcOccupied(MetroState state, RailPath path, double headArc, int cars, MetroConfig config) {
        double clearance = config.carSpacing;
        for (int i = 0; i < cars; i++) {
            Vec3d point = path.sample(Math.max(0.0, headArc - i * config.carSpacing)).pos();
            for (Consist train : state.trains()) {
                if (train.anyCarWithin(point, clearance)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Removes every train serving the line, returning how many trains were removed.
    public static int removeByLine(ServerWorld world, String lineId) {
        MetroState state = MetroState.of(world);
        List<UUID> ids = new ArrayList<>();
        for (Consist consist : state.trains()) {
            if (lineId(consist.lineName()).equalsIgnoreCase(lineId)) {
                ids.add(consist.id());
            }
        }
        for (UUID id : ids) {
            state.removeTrain(id);
        }
        return ids.size();
    }

    // Line ids of the trains in a world, for the remove command's autocomplete.
    public static Set<String> activeLineIds(ServerWorld world) {
        Set<String> ids = new LinkedHashSet<>();
        for (Consist consist : MetroState.of(world).trains()) {
            String id = lineId(consist.lineName());
            if (!id.isEmpty()) {
                ids.add(id);
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

    // Removes every train and any loaded car, returning the number of trains removed.
    public static int removeAll(ServerWorld world) {
        for (MetroCarEntity car : world.getEntitiesByType(ModEntities.METRO_CAR, car -> true)) {
            car.discard();
        }
        cancelCirculations(world);
        return MetroState.of(world).removeAllTrains();
    }

    // Removes the train with the car nearest a position, loaded or not.
    public static boolean removeNearest(ServerWorld world, Vec3d pos) {
        MetroState state = MetroState.of(world);
        Consist nearest = null;
        double best = Double.MAX_VALUE;
        for (Consist consist : state.trains()) {
            double distance = consist.nearestCarSq(pos);
            if (distance < best) {
                best = distance;
                nearest = consist;
            }
        }
        if (nearest == null) {
            return false;
        }
        return state.removeTrain(nearest.id());
    }

    // Rebakes the paths of every train, or only of the trains on one line, returning how many paths were marked.
    public static int rebake(ServerWorld world, String lineId) {
        MetroState state = MetroState.of(world);
        int marked = state.markAllDirty(lineId);
        state.rebakeDirty(false);
        return marked;
    }

    // One summary line per train for the list command.
    public static List<String> describeTrains(ServerWorld world) {
        List<String> lines = new ArrayList<>();
        for (Consist consist : MetroState.of(world).trains()) {
            lines.add(consist.describe());
        }
        return lines;
    }

    // Removes every view before the server stops, leaving non-player riders in the world.
    private static void despawnAllViews(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            MetroState state = MetroState.peek(world);
            if (state != null) {
                for (Consist consist : state.trains()) {
                    consist.despawnViews();
                }
            }
        }
    }

    // Dismounts non-player riders from the views of a world, keeping the views.
    public static void dismountMobs(ServerWorld world) {
        MetroState state = MetroState.peek(world);
        if (state != null) {
            for (Consist consist : state.trains()) {
                consist.dismountMobs();
            }
        }
    }

    // Redraws a train a player reports as broken, only once the whole train is inside that player's simulation distance.
    public static void redraw(ServerWorld world, UUID id, Vec3d playerPos) {
        MetroState state = MetroState.peek(world);
        Consist train = state == null ? null : state.train(id);
        int chunks = world.getServer().getPlayerManager().getSimulationDistance();
        if (train != null && train.allCarsWithinChunks(playerPos, chunks)) {
            train.redraw();
        }
    }

    // Schedules a redraw of every train near a player who changed world or jumped far in one tick.
    private static void watchTeleports(MinecraftServer server) {
        Map<UUID, Sighting> seen = new HashMap<>();
        int chunks = server.getPlayerManager().getSimulationDistance();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getEntityWorld();
            Vec3d pos = player.getEntityPos();
            Sighting last = LAST_SEEN.get(player.getUuid());
            seen.put(player.getUuid(), new Sighting(world, pos));
            if (last == null || player.getVehicle() instanceof MetroCarEntity) {
                continue;
            }
            if (last.world() == world && last.pos().squaredDistanceTo(pos) < TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
                continue;
            }
            MetroState state = MetroState.peek(world);
            if (state == null) {
                continue;
            }
            for (Consist consist : state.trains()) {
                if (consist.anyCarWithinChunks(pos, chunks)) {
                    consist.scheduleRedraw(REDRAW_DELAY);
                }
            }
        }
        LAST_SEEN.clear();
        LAST_SEEN.putAll(seen);
    }

    // Advances the world's circulations and drops the finished ones.
    private static void tickCirculations(ServerWorld world, MetroState state) {
        Iterator<Circulation> it = CIRCULATIONS.iterator();
        while (it.hasNext()) {
            Circulation circulation = it.next();
            if (circulation.world() != world) {
                continue;
            }
            circulation.tick(state, MmsMetro.config());
            if (circulation.finished()) {
                it.remove();
            }
        }
    }

    // Stops every circulation in a world.
    private static void cancelCirculations(ServerWorld world) {
        CIRCULATIONS.removeIf(circulation -> circulation.world() == world);
    }

    private static void tickWorld(ServerWorld world) {
        MetroState state = LegacyCars.hasPending(world) ? MetroState.of(world) : MetroState.peek(world);
        if (state == null) {
            return;
        }
        LegacyCars.migrate(world, state);
        TrainTickets.update(world, state.trains());
        tickCirculations(world, state);
        if (state.trains().isEmpty()) {
            return;
        }

        for (Consist consist : new ArrayList<>(state.trains())) {
            consist.tick();
            consist.syncViews();
            consist.tickRedraw(world.getServer().getPlayerManager().getSimulationDistance());
        }
        state.rebakeIfDue();
        state.markDirty();
    }
}
