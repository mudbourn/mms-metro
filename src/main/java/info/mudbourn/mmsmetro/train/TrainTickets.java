package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.registry.ModTickets;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// Holds one small ticket on each chunk a train's cars occupy, kept a while after the train leaves.
final class TrainTickets {

    // Ticket radius that entity-ticks only the centre chunk.
    private static final int RADIUS = 2;

    // Ticks a ticket outlives the last car leaving its chunk.
    private static final long LINGER_TICKS = 100;

    private static final Map<ServerWorld, Long2LongMap> HELD = new HashMap<>();

    private TrainTickets() {
    }

    static void update(ServerWorld world, Collection<Consist> trains) {
        LongSet occupied = new LongOpenHashSet();
        for (Consist train : trains) {
            train.collectCarChunks(occupied);
        }

        Long2LongMap held = HELD.computeIfAbsent(world, w -> new Long2LongOpenHashMap());
        long now = world.getTime();
        for (long chunk : occupied) {
            if (held.put(chunk, now + LINGER_TICKS) == held.defaultReturnValue()) {
                world.getChunkManager().addTicket(ModTickets.TRAIN, new ChunkPos(chunk), RADIUS);
            }
        }

        ObjectIterator<Long2LongMap.Entry> it = ((Long2LongOpenHashMap) held).long2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2LongMap.Entry entry = it.next();
            if (entry.getLongValue() < now) {
                world.getChunkManager().removeTicket(ModTickets.TRAIN, new ChunkPos(entry.getLongKey()), RADIUS);
                it.remove();
            }
        }
    }

    static void clear() {
        HELD.clear();
    }
}
