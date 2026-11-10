package info.mudbourn.mmsmetro.compat.cc;

import dan200.computercraft.api.peripheral.PeripheralLookup;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import info.mudbourn.mmsmetro.registry.ModBlockEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

// CC:Tweaked integration, only loaded when that mod is present.
public final class MetroComputerCraft {

    // Stations loaded this tick whose neighbouring computers and modems still need a block update.
    private static final Map<ServerWorld, List<BlockPos>> PENDING = new HashMap<>();

    private MetroComputerCraft() {
    }

    public static void register() {
        PeripheralLookup.get().registerForBlockEntity(
            (station, side) -> new StationPeripheral(station),
            ModBlockEntities.STATION
        );
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof StationBlockEntity station) {
                PENDING.computeIfAbsent(world, w -> new ArrayList<>()).add(station.getPos());
            }
        });
        ServerTickEvents.END_WORLD_TICK.register(MetroComputerCraft::notifyLoaded);
    }

    // Pokes the neighbours of freshly loaded stations so computers re-attach the peripheral without a reboot.
    private static void notifyLoaded(ServerWorld world) {
        List<BlockPos> loaded = PENDING.remove(world);
        if (loaded == null) {
            return;
        }
        for (BlockPos pos : loaded) {
            if (world.isChunkLoaded(pos) && world.getBlockEntity(pos) instanceof StationBlockEntity) {
                world.updateNeighbors(pos, world.getBlockState(pos).getBlock());
            }
        }
    }
}
