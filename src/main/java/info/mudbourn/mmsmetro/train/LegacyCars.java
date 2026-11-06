package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.path.PathKey;
import info.mudbourn.mmsmetro.path.RailPath;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Turns car entities saved by older versions into saved trains and discards them.
final class LegacyCars {

    private static final Map<ServerWorld, List<MetroCarEntity>> PENDING = new HashMap<>();

    // Trains created from a non-lead car.
    private static final Set<UUID> APPROXIMATE = new HashSet<>();

    private LegacyCars() {
    }

    static boolean hasPending(ServerWorld world) {
        return PENDING.containsKey(world);
    }

    static void clear() {
        PENDING.clear();
        APPROXIMATE.clear();
    }

    // Queues a saved car that loads with its chunk.
    static void onLoad(Entity entity, ServerWorld world) {
        if (entity instanceof MetroCarEntity car && !car.isView()) {
            PENDING.computeIfAbsent(world, w -> new ArrayList<>()).add(car);
        }
    }

    // Adopts the queued cars of a world into its state, then discards them.
    static void migrate(ServerWorld world, MetroState state) {
        List<MetroCarEntity> cars = PENDING.remove(world);
        if (cars == null) {
            return;
        }
        for (MetroCarEntity car : cars) {
            if (!car.isRemoved()) {
                adopt(world, state, car);
                car.discard();
            }
        }
    }

    private static void adopt(ServerWorld world, MetroState state, MetroCarEntity car) {
        Consist existing = state.train(car.getConsistId());
        if (existing != null) {
            existing.ensureCars(car.getCarIndex() + 1);
            if (car.getCarIndex() == 0 && APPROXIMATE.remove(existing.id())) {
                existing.setHeadArc(car.getArcLength());
            }
            return;
        }

        MetroConfig config = MmsMetro.config();
        RailPath path = null;
        double carArc = 0.0;
        BlockPos origin = car.getPathOrigin();
        Direction dir = car.getPathInitialDir();
        if (origin != null && dir != null) {
            path = state.pathFor(new PathKey(origin, dir));
            carArc = car.getArcLength();
        }
        if (path == null) {
            BlockPos rail = ConsistManager.findRail(world, car.getBlockPos());
            if (rail == null) {
                return;
            }
            path = state.pathFor(new PathKey(rail, Direction.fromHorizontalDegrees(car.getPathYaw())));
            if (path == null) {
                return;
            }
            List<Double> arcs = path.arcsOnTrack(rail);
            carArc = arcs.isEmpty() ? 0.0 : arcs.get(0);
        }

        double headArc = Math.min(carArc + car.getCarIndex() * config.carSpacing, path.length());
        Consist train = new Consist(car.getConsistId(), config, path, headArc, car.getCarIndex() + 1);
        state.addTrain(train);
        if (car.getCarIndex() != 0) {
            APPROXIMATE.add(train.id());
        }
    }
}
