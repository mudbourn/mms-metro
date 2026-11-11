package info.mudbourn.mmsmetro.station;

import com.mojang.serialization.Codec;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import info.mudbourn.mmsmetro.train.ConsistManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A saved index of every station block each world has loaded, with its line id, so a line can be found by its id alone even where its chunks are unloaded.
public final class StationIndex extends PersistentState {

    private static final Codec<StationIndex> CODEC = NbtCompound.CODEC.xmap(StationIndex::fromNbt, StationIndex::toNbt);

    private static final PersistentStateType<StationIndex> TYPE = new PersistentStateType<>(
        "mms_metro_stations",
        StationIndex::new,
        CODEC,
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<BlockPos, String> lines = new LinkedHashMap<>();

    public static void init() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(StationIndex::record);
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(StationIndex::record);
    }

    private static StationIndex of(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }

    // Records a station block's current line id as it loads or unloads.
    private static void record(BlockEntity blockEntity, ServerWorld world) {
        if (blockEntity instanceof StationBlockEntity station) {
            of(world).put(station.getPos().toImmutable(), ConsistManager.lineId(station.getLineName()));
        }
    }

    private void put(BlockPos pos, String lineId) {
        if (!lineId.equals(this.lines.put(pos, lineId))) {
            markDirty();
        }
    }

    private void forget(BlockPos pos) {
        if (this.lines.remove(pos) != null) {
            markDirty();
        }
    }

    // Positions of the station blocks whose line id matches, loading the chunks of matching unloaded ones and dropping entries whose block is gone.
    public static List<BlockPos> stationsOnLine(ServerWorld world, String lineId) {
        StationIndex index = of(world);
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, String> entry : new ArrayList<>(index.lines.entrySet())) {
            BlockPos pos = entry.getKey();
            ChunkPos chunk = new ChunkPos(pos);
            boolean loaded = world.getChunkManager().isChunkLoaded(chunk.x, chunk.z);
            if (!loaded && !entry.getValue().equalsIgnoreCase(lineId)) {
                continue;
            }
            if (!(world.getBlockEntity(pos) instanceof StationBlockEntity station)) {
                index.forget(pos);
                continue;
            }
            String live = ConsistManager.lineId(station.getLineName());
            index.put(pos, live);
            if (live.equalsIgnoreCase(lineId)) {
                out.add(pos);
            }
        }
        return out;
    }

    // Every line id with a known station block in the world, for command autocomplete.
    public static Set<String> lineIds(ServerWorld world) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : of(world).lines.values()) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private NbtCompound toNbt() {
        NbtList list = new NbtList();
        for (Map.Entry<BlockPos, String> entry : this.lines.entrySet()) {
            NbtCompound station = new NbtCompound();
            station.putLong("Pos", entry.getKey().asLong());
            station.putString("Line", entry.getValue());
            list.add(station);
        }
        NbtCompound nbt = new NbtCompound();
        nbt.put("Stations", list);
        return nbt;
    }

    private static StationIndex fromNbt(NbtCompound nbt) {
        StationIndex index = new StationIndex();
        NbtList list = nbt.getListOrEmpty("Stations");
        for (int i = 0; i < list.size(); i++) {
            NbtCompound station = list.getCompoundOrEmpty(i);
            index.lines.put(BlockPos.fromLong(station.getLong("Pos", 0L)), station.getString("Line", ""));
        }
        return index;
    }
}
