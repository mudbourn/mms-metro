package info.mudbourn.mmsmetro.compat.cc;

import dan200.computercraft.api.lua.LuaException;
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
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

// Exposes a station block and the trains heading to it to CC:Tweaked computers as the "metro_station" peripheral.
public final class StationPeripheral implements IPeripheral {

    private static final int TICKS_PER_SECOND = 20;

    private final ServerWorld world;

    private final BlockPos pos;

    public StationPeripheral(StationBlockEntity station) {
        this.world = (ServerWorld) station.getWorld();
        this.pos = station.getPos().toImmutable();
    }

    // The live station block entity at this position, so a reloaded or replaced one is picked up.
    private StationBlockEntity station() throws LuaException {
        if (this.world.isChunkLoaded(this.pos) && this.world.getBlockEntity(this.pos) instanceof StationBlockEntity station) {
            return station;
        }
        throw new LuaException("Station is not loaded");
    }

    @Override
    public String getType() {
        return "metro_station";
    }

    @Override
    public Object getTarget() {
        return this.pos;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof StationPeripheral peripheral && peripheral.world == this.world && peripheral.pos.equals(this.pos);
    }

    // The station's settings as set in its editor.
    @LuaFunction(mainThread = true)
    public Map<String, Object> getStation() throws LuaException {
        StationBlockEntity station = station();
        Map<String, Object> info = new HashMap<>();
        info.put("name", station.getStationName());
        info.put("line", station.getLineName());
        info.put("lineColor", station.getLineColor());
        info.put("direction", station.getLineDirection());
        info.put("nextStation", station.getNextStation());
        info.put("exitSide", station.getExitDirection());
        info.put("hub", station.isHub());
        info.put("transferLine", station.getTransferLine());
        info.put("terminus", station.isTerminus());
        info.put("dwellSeconds", station.getDwellTicks() / (double) TICKS_PER_SECOND);
        return info;
    }

    // Most predicted calls listed per train, so one train can fill several rows of a board.
    private static final int VISITS_PER_TRAIN = 4;

    // Calls at this stop, soonest first, each train repeated for its later laps or return trips, with an ETA in seconds that counts acceleration, braking and dwells.
    @LuaFunction(mainThread = true)
    public List<Map<String, Object>> getArrivals() throws LuaException {
        station();
        List<Map<String, Object>> arrivals = new ArrayList<>();
        for (Consist consist : ConsistManager.trains(this.world)) {
            for (Consist.Visit visit : consist.upcomingVisits(this.pos, VISITS_PER_TRAIN)) {
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
