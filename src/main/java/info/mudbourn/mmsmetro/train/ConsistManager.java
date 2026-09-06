package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.server.world.ServerWorld;
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
    }

    public static Consist spawn(ServerWorld world, BlockPos rail, Direction facing, int cars, MetroConfig config) {
        RailPath path = RailPath.build(world, rail, facing, MAX_PATH_NODES);
        if (path.length() <= 0.0) {
            return null;
        }

        double initialHead = Math.min((cars - 1) * config.carSpacing, path.length());
        Consist consist = new Consist(path, config, initialHead);
        for (int i = 0; i < cars; i++) {
            MetroCarEntity car = new MetroCarEntity(ModEntities.METRO_CAR, world);
            car.setCarIndex(i);
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

    public static int removeAll(ServerWorld world) {
        List<Consist> list = BY_WORLD.remove(world);
        if (list == null) {
            return 0;
        }

        for (Consist consist : list) {
            consist.discard();
        }
        return list.size();
    }

    public static boolean removeNearest(ServerWorld world, Vec3d pos) {
        List<Consist> list = BY_WORLD.get(world);
        if (list == null || list.isEmpty()) {
            return false;
        }

        Consist nearest = null;
        double best = Double.MAX_VALUE;
        for (Consist consist : list) {
            for (MetroCarEntity car : consist.cars()) {
                double distance = car.squaredDistanceTo(pos);
                if (distance < best) {
                    best = distance;
                    nearest = consist;
                }
            }
        }

        if (nearest == null) {
            return false;
        }

        nearest.discard();
        list.remove(nearest);
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
