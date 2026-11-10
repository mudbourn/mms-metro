package info.mudbourn.mmsmetro.compat.cc;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
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

    // Most predicted calls listed per train, so one train can fill several rows of a board.
    private static final int VISITS_PER_TRAIN = 4;

    // Calls at this stop, soonest first, each train repeated for its later laps or return trips, with an ETA in seconds that counts acceleration, braking and dwells.
    @LuaFunction(mainThread = true)
    public List<Map<String, Object>> getArrivals() {
        List<Map<String, Object>> arrivals = new ArrayList<>();
        if (!(this.station.getWorld() instanceof ServerWorld world)) {
            return arrivals;
        }
        for (Consist consist : ConsistManager.trains(world)) {
            for (Consist.Visit visit : consist.upcomingVisits(this.station.getPos(), VISITS_PER_TRAIN)) {
                Map<String, Object> arrival = new HashMap<>();
                arrival.put("line", consist.lineName());
                arrival.put("direction", visit.direction());
                arrival.put("terminal", visit.terminal());
                arrival.put("cars", consist.carCount());
                arrival.put("eta", Math.round(visit.ticks() / TICKS_PER_SECOND));
                arrival.put("atPlatform", visit.atPlatform());
                arrival.put("departsIn", visit.atPlatform()
                    ? Math.round(consist.ticksUntilDeparture() / (double) TICKS_PER_SECOND)
                    : 0L);
                arrivals.add(arrival);
            }
        }
        arrivals.sort((a, b) -> Long.compare((Long) a.get("eta"), (Long) b.get("eta")));
        return arrivals;
    }
}
