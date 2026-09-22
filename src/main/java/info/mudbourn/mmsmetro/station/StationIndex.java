package info.mudbourn.mmsmetro.station;

import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import info.mudbourn.mmsmetro.train.ConsistManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A live index of the station blocks loaded in each world, so a line can be found by its id alone rather than from where a player stands or looks.
public final class StationIndex {

    private static final Map<ServerWorld, Set<BlockPos>> STATIONS = new HashMap<>();

    private StationIndex() {
    }

    public static void init() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof StationBlockEntity) {
                STATIONS.computeIfAbsent(world, w -> new HashSet<>()).add(blockEntity.getPos().toImmutable());
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof StationBlockEntity) {
                Set<BlockPos> set = STATIONS.get(world);
                if (set != null) {
                    set.remove(blockEntity.getPos());
                }
            }
        });
    }

    // Positions of the loaded station blocks whose line id matches, read live from each block entity.
    public static List<BlockPos> stationsOnLine(ServerWorld world, String lineId) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> set = STATIONS.get(world);
        if (set == null) {
            return out;
        }
        for (BlockPos pos : set) {
            if (world.getBlockEntity(pos) instanceof StationBlockEntity station
                    && ConsistManager.lineId(station.getLineName()).equalsIgnoreCase(lineId)) {
                out.add(pos);
            }
        }
        return out;
    }

    // Every line id with a loaded station block in the world, for command autocomplete.
    public static Set<String> lineIds(ServerWorld world) {
        Set<String> ids = new LinkedHashSet<>();
        Set<BlockPos> set = STATIONS.get(world);
        if (set == null) {
            return ids;
        }
        for (BlockPos pos : set) {
            if (world.getBlockEntity(pos) instanceof StationBlockEntity station) {
                String id = ConsistManager.lineId(station.getLineName());
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
