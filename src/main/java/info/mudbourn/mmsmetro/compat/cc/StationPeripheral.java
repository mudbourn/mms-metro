package info.mudbourn.mmsmetro.compat.cc;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import info.mudbourn.mmsmetro.train.Consist;
import info.mudbourn.mmsmetro.train.ConsistManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.world.ServerWorld;
import org.jspecify.annotations.Nullable;

// Exposes a station block and the trains heading to it to CC:Tweaked computers as the "metro_station" peripheral.
public final class StationPeripheral implements IPeripheral {

    private static final int TICKS_PER_SECOND = 20;

    private final StationBlockEntity station;

    public StationPeripheral(StationBlockEntity station) {
        this.station = station;
    }

    @Override
    public String getType() {
        return "metro_station";
    }

    @Override
    public Object getTarget() {
        return this.station;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof StationPeripheral peripheral && peripheral.station == this.station;
    }

    // The station's settings as set in its editor.
    @LuaFunction(mainThread = true)
    public Map<String, Object> getStation() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", this.station.getStationName());
        info.put("line", this.station.getLineName());
        info.put("lineColor", this.station.getLineColor());
        info.put("direction", this.station.getLineDirection());
        info.put("nextStation", this.station.getNextStation());
        info.put("exitSide", this.station.getExitDirection());
        info.put("hub", this.station.isHub());
        info.put("transferLine", this.station.getTransferLine());
        info.put("terminus", this.station.isTerminus());
        info.put("dwellSeconds", this.station.getDwellTicks() / (double) TICKS_PER_SECOND);
        return info;
    }

    // Trains that will stop here, nearest first, with distance in blocks and a rough ETA in seconds at top speed.
    @LuaFunction(mainThread = true)
    public List<Map<String, Object>> getArrivals() {
        List<Map<String, Object>> arrivals = new ArrayList<>();
        if (!(this.station.getWorld() instanceof ServerWorld world)) {
            return arrivals;
        }
        double blocksPerSecond = Math.max(1.0e-3, MmsMetro.config().maxSpeed * TICKS_PER_SECOND);
        for (Consist consist : ConsistManager.liveConsists(world)) {
            double distance = consist.distanceToStation(this.station.getPos());
            if (distance < 0.0) {
                continue;
            }
            Map<String, Object> arrival = new HashMap<>();
            arrival.put("line", consist.lineName());
            arrival.put("direction", consist.directionName());
            arrival.put("cars", consist.cars().size());
            arrival.put("distance", Math.round(distance));
            arrival.put("eta", Math.round(distance / blocksPerSecond));
            arrival.put("atPlatform", distance == 0.0);
            arrival.put("departsIn", Math.round(consist.ticksUntilDeparture() / (double) TICKS_PER_SECOND));
            arrivals.add(arrival);
        }
        arrivals.sort((a, b) -> Long.compare((Long) a.get("distance"), (Long) b.get("distance")));
        return arrivals;
    }
}
