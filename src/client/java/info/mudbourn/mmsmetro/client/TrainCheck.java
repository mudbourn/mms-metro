package info.mudbourn.mmsmetro.client;

import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Looks over the trains near the player and asks the server to redraw any whose cars are missing, doubled, split apart or stuck.
public final class TrainCheck {

    // Client ticks between checks.
    private static final int INTERVAL = 10;

    // Client ticks before the same train may be reported again.
    private static final long REPORT_COOLDOWN = 100;

    // Blocks two neighbouring cars may sit further apart than their arc gap.
    private static final double SPREAD_SLACK = 2.0;

    // Arc change below which a car counts as not moving.
    private static final float STILL_ARC = 0.001f;

    private static final Int2FloatMap LAST_ARC = new Int2FloatOpenHashMap();

    private static final Map<UUID, Long> LAST_REPORT = new HashMap<>();

    private static long ticks;

    private TrainCheck() {
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            LAST_ARC.clear();
            LAST_REPORT.clear();
            return;
        }
        if (++ticks % INTERVAL != 0) {
            return;
        }
        double range = client.options.getSimulationDistance().getValue() * 16.0;
        double rangeSq = range * range;
        Map<UUID, List<MetroCarEntity>> trains = new HashMap<>();
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof MetroCarEntity car && car.getConsistId() != null
                && car.squaredDistanceTo(client.player) <= rangeSq) {
                trains.computeIfAbsent(car.getConsistId(), id -> new ArrayList<>()).add(car);
            }
        }
        Int2FloatMap seen = new Int2FloatOpenHashMap();
        for (Map.Entry<UUID, List<MetroCarEntity>> train : trains.entrySet()) {
            if (broken(train.getValue(), seen) && canReport(train.getKey())) {
                ClientPlayNetworking.send(new MetroNetworking.RedrawTrain(train.getKey()));
            }
        }
        LAST_ARC.clear();
        LAST_ARC.putAll(seen);
    }

    private static boolean canReport(UUID id) {
        Long last = LAST_REPORT.get(id);
        if (last != null && ticks - last < REPORT_COOLDOWN) {
            return false;
        }
        LAST_REPORT.put(id, ticks);
        return true;
    }

    // True when the visible cars of one train are doubled, have a gap, sit too far apart, or some move while others are frozen.
    private static boolean broken(List<MetroCarEntity> cars, Int2FloatMap seen) {
        cars.sort(Comparator.comparingInt(MetroCarEntity::getCarIndex));
        boolean anyMoved = false;
        boolean anyStill = false;
        for (int i = 0; i < cars.size(); i++) {
            MetroCarEntity car = cars.get(i);
            float arc = car.getArcLength();
            seen.put(car.getId(), arc);
            if (LAST_ARC.containsKey(car.getId())) {
                boolean still = Math.abs(arc - LAST_ARC.get(car.getId())) < STILL_ARC;
                anyStill |= still;
                anyMoved |= !still;
            }
            if (i == 0) {
                continue;
            }
            MetroCarEntity prev = cars.get(i - 1);
            if (car.getCarIndex() - prev.getCarIndex() != 1) {
                return true;
            }
            double arcGap = Math.abs(car.getArcLength() - prev.getArcLength());
            if (car.distanceTo(prev) > arcGap + SPREAD_SLACK) {
                return true;
            }
        }
        return anyMoved && anyStill;
    }
}
