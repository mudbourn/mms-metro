package info.mudbourn.mmsmetro.compat.cc;

import dan200.computercraft.api.peripheral.PeripheralLookup;
import info.mudbourn.mmsmetro.registry.ModBlockEntities;

// CC:Tweaked integration, only loaded when that mod is present.
public final class MetroComputerCraft {

    private MetroComputerCraft() {
    }

    public static void register() {
        PeripheralLookup.get().registerForBlockEntity(
            (station, side) -> new StationPeripheral(station),
            ModBlockEntities.STATION
        );
    }
}
