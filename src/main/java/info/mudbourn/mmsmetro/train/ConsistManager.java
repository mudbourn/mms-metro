package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.item.MetroSpawnerItem;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
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

    // Chunks this mod currently keeps force-loaded per world, keyed by ChunkPos long, so the moving set can be diffed each tick.
    private static final Map<ServerWorld, java.util.Set<Long>> FORCED_CHUNKS = new HashMap<>();

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

    public static Consist spawn(ServerWorld world, BlockPos rail, Direction facing, int cars, MetroConfig config) {
        // Head toward whichever direction reaches a station soonest, falling back to the way the player was facing when neither side has one.
        RailPath forward = RailPath.build(world, rail, facing, RailPath.MAX_NODES);
        RailPath backward = RailPath.build(world, rail, facing.getOpposite(), RailPath.MAX_NODES);
        RailPath path = backward.nearestStationArc() < forward.nearestStationArc() ? backward : forward;
        if (path.length() <= 0.0) {
            path = forward.length() > 0.0 ? forward : backward;
        }
        if (path.length() <= 0.0) {
            return null;
        }

        double initialHead = Math.min((cars - 1) * config.carSpacing, path.length());
        Consist consist = new Consist(path, config, initialHead);
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

        List<Consist> list = BY_WORLD.get(world);
        if (list != null) {
            Iterator<Consist> it = list.iterator();
            while (it.hasNext()) {
                Consist consist = it.next();
                if (consist.isFinished()) {
                    it.remove();
                } else {
                    consist.tick();
                }
            }
        }

        updateForcedChunks(world, list);
    }

    // Keeps the chunks around every live train force-loaded so a moving consist and the rail ahead never fall into an unloaded chunk, which would strand cars and truncate the walked path; the desired set is rebuilt from car positions each tick and diffed against the currently forced set.
    private static void updateForcedChunks(ServerWorld world, List<Consist> list) {
        java.util.Set<Long> desired = new java.util.HashSet<>();
        int radius = MmsMetro.config().chunkRadius;
        if (list != null) {
            for (Consist consist : list) {
                for (MetroCarEntity car : consist.cars()) {
                    if (car.isRemoved()) {
                        continue;
                    }
                    int cx = car.getBlockPos().getX() >> 4;
                    int cz = car.getBlockPos().getZ() >> 4;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            desired.add(net.minecraft.util.math.ChunkPos.toLong(cx + dx, cz + dz));
                        }
                    }
                }
            }
        }

        java.util.Set<Long> current = FORCED_CHUNKS.getOrDefault(world, java.util.Set.of());
        for (long key : desired) {
            if (!current.contains(key)) {
                world.setChunkForced(net.minecraft.util.math.ChunkPos.getPackedX(key),
                    net.minecraft.util.math.ChunkPos.getPackedZ(key), true);
            }
        }
        for (long key : current) {
            if (!desired.contains(key)) {
                world.setChunkForced(net.minecraft.util.math.ChunkPos.getPackedX(key),
                    net.minecraft.util.math.ChunkPos.getPackedZ(key), false);
            }
        }

        if (desired.isEmpty()) {
            FORCED_CHUNKS.remove(world);
        } else {
            FORCED_CHUNKS.put(world, desired);
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

        BlockPos rail = findRail(world, tail.getBlockPos());
        if (rail == null) {
            rail = findRail(world, lead.getBlockPos());
        }
        if (rail == null) {
            return null;
        }

        Direction heading = headingOf(lead, tail);
        RailPath path = RailPath.build(world, rail, heading, RailPath.MAX_NODES);
        if (path.length() <= 0.0) {
            return null;
        }

        MetroConfig config = MmsMetro.config();
        double headArc = Math.min((cars.size() - 1) * config.carSpacing, path.length());
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
