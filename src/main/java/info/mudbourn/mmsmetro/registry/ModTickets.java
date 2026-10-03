package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.Identifier;

// Registers the mod's chunk ticket types.
public final class ModTickets {

    // Keeps the chunks around a live train loaded and simulated; never serialized, so tickets die with the server instead of leaking into chunks.dat the way forceload tickets did across restarts.
    public static ChunkTicketType TRAIN;

    public static void register() {
        TRAIN = Registry.register(
            Registries.TICKET_TYPE,
            Identifier.of(MmsMetro.MOD_ID, "train"),
            new ChunkTicketType(ChunkTicketType.NO_EXPIRATION, ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION)
        );
    }
}
