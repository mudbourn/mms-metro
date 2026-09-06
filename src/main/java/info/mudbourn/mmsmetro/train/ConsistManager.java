package info.mudbourn.mmsmetro.train;

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

    private static final int MAX_PATH_NODES = 512;

    private static final Map<ServerWorld, List<Consist>> BY_WORLD = new HashMap<>();

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
            removeConsist(serverWorld, car.getConsistId());
        }
        return ActionResult.SUCCESS;
    }

    // Every loaded metro car in the world, whether or not it is still tracked
    // in the in-memory registry (which is empty after a reload).
    private static List<? extends MetroCarEntity> allCars(ServerWorld world) {
        return world.getEntitiesByType(ModEntities.METRO_CAR, car -> true);
    }

    // Discards every car sharing a consist id and drops that consist from the
    // live registry. Returns the number of cars removed.
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
        RailPath path = RailPath.build(world, rail, facing, MAX_PATH_NODES);
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
        List<Consist> list = BY_WORLD.get(world);
        if (list == null) {
            return;
        }

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
}
