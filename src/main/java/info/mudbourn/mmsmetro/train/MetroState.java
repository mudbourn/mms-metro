package info.mudbourn.mmsmetro.train;

import com.mojang.serialization.Codec;
import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.JunctionBlock;
import info.mudbourn.mmsmetro.block.SpeakerBlock;
import info.mudbourn.mmsmetro.block.SpeedBumpBlock;
import info.mudbourn.mmsmetro.block.StationBlock;
import info.mudbourn.mmsmetro.path.PathKey;
import info.mudbourn.mmsmetro.path.PathReverse;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// The saved trains of one world: baked paths shared by trains, and the trains themselves.
public final class MetroState extends PersistentState {

    private static final Codec<MetroState> CODEC = NbtCompound.CODEC.xmap(MetroState::fromNbt, MetroState::toNbt);

    // Saved state types per dimension, since every dimension shares the overworld's state manager.
    private static final Map<RegistryKey<World>, PersistentStateType<MetroState>> TYPES = new HashMap<>();

    // Most paths baked by one request, so a pathological network cannot bake forever.
    private static final int BAKE_LIMIT = 32;

    // Ticks between rebakes of dirty paths.
    private static final int REBAKE_INTERVAL = 20;

    private final Map<PathKey, RailPath> paths = new LinkedHashMap<>();

    private final Map<UUID, Consist> trains = new LinkedHashMap<>();

    // Departure logs per terminus station, kept in memory to space departures evenly.
    private final Map<Long, TerminusLog> termini = new HashMap<>();

    private final Set<PathKey> dirty = new LinkedHashSet<>();

    private ServerWorld world;

    private long lastRebakeTick;

    // The world's state, bound to the world so its trains can read it.
    public static MetroState of(ServerWorld world) {
        MetroState state = world.getPersistentStateManager().getOrCreate(type(world));
        state.bind(world);
        return state;
    }

    // The world's state if one exists already, else null.
    static MetroState peek(ServerWorld world) {
        MetroState state = world.getPersistentStateManager().get(type(world));
        if (state != null) {
            state.bind(world);
        }
        return state;
    }

    // The state type of a world's dimension, keeping the original id for the overworld.
    private static PersistentStateType<MetroState> type(ServerWorld world) {
        return TYPES.computeIfAbsent(world.getRegistryKey(), key -> new PersistentStateType<>(
            key == World.OVERWORLD ? "mms_metro_trains" : "mms_metro_trains_" + key.getValue().toUnderscoreSeparatedString(),
            MetroState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
        ));
    }

    private void bind(ServerWorld bound) {
        if (this.world == bound) {
            return;
        }
        this.world = bound;
        for (Consist train : this.trains.values()) {
            train.attach(bound, this);
        }
    }

    // Moves each train of the overworld file whose origin rail is absent there but present in exactly one other dimension into that dimension.
    static void migrateStrays(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        MetroState home = of(overworld);
        for (Consist train : new ArrayList<>(home.trains.values())) {
            BlockPos origin = train.path().key().origin();
            if (AbstractRailBlock.isRail(overworld, origin)) {
                continue;
            }
            List<ServerWorld> owners = new ArrayList<>();
            for (ServerWorld world : server.getWorlds()) {
                if (world != overworld && AbstractRailBlock.isRail(world, origin)) {
                    owners.add(world);
                }
            }
            if (owners.size() != 1) {
                continue;
            }
            ServerWorld target = owners.get(0);
            home.trains.remove(train.id());
            train.discardViews();
            MetroState destination = of(target);
            destination.paths.putIfAbsent(train.path().key(), train.path());
            destination.addTrain(train);
            MmsMetro.LOGGER.info("Moved train {} from the overworld file to {}", train.id(), target.getRegistryKey().getValue());
        }
        home.collectPaths();
        home.markDirty();
    }

    public Collection<Consist> trains() {
        return this.trains.values();
    }

    public Consist train(UUID id) {
        return this.trains.get(id);
    }

    // The departure log of the terminus at a station block.
    TerminusLog terminus(BlockPos pos) {
        return this.termini.computeIfAbsent(pos.asLong(), key -> new TerminusLog());
    }

    void addTrain(Consist train) {
        train.attach(this.world, this);
        this.trains.put(train.id(), train);
        markDirty();
    }

    // Drops a train and its views, returning false when it was not present.
    boolean removeTrain(UUID id) {
        Consist train = this.trains.remove(id);
        if (train == null) {
            return false;
        }
        train.discardViews();
        collectPaths();
        markDirty();
        return true;
    }

    // Drops every train, returning how many there were.
    int removeAllTrains() {
        int count = this.trains.size();
        for (Consist train : this.trains.values()) {
            train.discardViews();
        }
        this.trains.clear();
        collectPaths();
        markDirty();
        return count;
    }

    // The baked path for a key, baking it and the turn-arounds it leads to when absent; null when no track lies there.
    public RailPath pathFor(PathKey key) {
        RailPath existing = this.paths.get(key);
        return existing != null ? existing : bakeChain(key, false);
    }

    // The baked path for a key if one is already held, never baking.
    RailPath bakedPath(PathKey key) {
        return this.paths.get(key);
    }

    // Like pathFor, but never loads a chunk; null when the path is absent and cannot be baked from loaded chunks.
    RailPath pathLoaded(PathKey key) {
        RailPath existing = this.paths.get(key);
        return existing != null ? existing : bakeChain(key, true);
    }

    // Bakes a path, or returns null when a strict bake met an unloaded chunk; an empty path means no track lies there.
    private RailPath bake(PathKey key, boolean loadedOnly) {
        RailPath path = loadedOnly
            ? RailPath.buildLoaded(this.world, key.origin(), key.dir(), RailPath.MAX_NODES)
            : RailPath.build(this.world, key.origin(), key.dir(), RailPath.MAX_NODES);
        if (path != null) {
            path.bakeReverses();
        }
        return path;
    }

    // Bakes a path and every turn-around path reachable from it that is not baked yet.
    private RailPath bakeChain(PathKey first, boolean loadedOnly) {
        Deque<PathKey> queue = new ArrayDeque<>();
        queue.add(first);
        int baked = 0;
        while (!queue.isEmpty() && baked < BAKE_LIMIT) {
            PathKey key = queue.poll();
            if (this.paths.containsKey(key)) {
                continue;
            }
            RailPath path = bake(key, loadedOnly);
            if (path == null || path.length() <= 0.0) {
                continue;
            }
            baked++;
            this.paths.put(key, path);
            for (PathReverse reverse : path.reverses()) {
                queue.add(reverse.key());
            }
        }
        markDirty();
        return this.paths.get(first);
    }

    // Marks every path running through the chunk of a position for the next rebake.
    public static void markPathsDirty(ServerWorld world, BlockPos pos) {
        MetroState state = peek(world);
        if (state == null) {
            return;
        }
        long chunk = ChunkPos.toLong(pos);
        for (RailPath path : state.paths.values()) {
            if (path.chunks().contains(chunk)) {
                state.dirty.add(path.key());
            }
        }
    }

    // Marks paths dirty when a block that shapes them changes.
    public static void onBlockChanged(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!isTrackBlock(oldState.getBlock()) && !isTrackBlock(newState.getBlock())) {
            return;
        }
        if (oldState.getBlock() == newState.getBlock()
            && oldState.getBlock() instanceof AbstractRailBlock rail
            && oldState.get(rail.getShapeProperty()) == newState.get(rail.getShapeProperty())) {
            return;
        }
        markPathsDirty(world, pos);
    }

    private static boolean isTrackBlock(Block block) {
        return block instanceof AbstractRailBlock
            || block instanceof StationBlock
            || block instanceof SpeedBumpBlock
            || block instanceof SpeakerBlock
            || block instanceof JunctionBlock;
    }

    // Marks every path dirty, or only those a train on the given line runs on.
    int markAllDirty(String lineId) {
        int marked = 0;
        for (RailPath path : this.paths.values()) {
            boolean wanted = lineId == null;
            for (Consist train : this.trains.values()) {
                if (train.path().key().equals(path.key())
                    && (wanted || ConsistManager.lineId(train.lineName()).equalsIgnoreCase(lineId))) {
                    wanted = true;
                }
            }
            if (wanted && this.dirty.add(path.key())) {
                marked++;
            }
        }
        return marked;
    }

    // Rebakes dirty paths once the interval since the last rebake has passed.
    void rebakeIfDue() {
        long now = this.world.getTime();
        if (this.dirty.isEmpty() || now - this.lastRebakeTick < REBAKE_INTERVAL) {
            return;
        }
        rebakeDirty(true);
    }

    // Rebakes every dirty path now; a loaded-only rebake leaves a path dirty when it meets an unloaded chunk.
    void rebakeDirty(boolean loadedOnly) {
        this.lastRebakeTick = this.world.getTime();
        List<PathKey> keys = new ArrayList<>(this.dirty);
        this.dirty.clear();
        for (PathKey key : keys) {
            rebake(key, loadedOnly);
        }
        collectPaths();
        markDirty();
    }

    private void rebake(PathKey key, boolean loadedOnly) {
        RailPath old = this.paths.get(key);
        if (old == null) {
            return;
        }
        RailPath fresh = bake(key, loadedOnly);
        if (fresh == null) {
            this.dirty.add(key);
            return;
        }
        if (fresh.length() <= 0.0) {
            for (Consist train : this.trains.values()) {
                if (train.path().key().equals(key)) {
                    train.markLost();
                }
            }
            return;
        }
        warnIfRerouted(old, fresh);
        this.paths.put(key, fresh);
        for (PathReverse reverse : fresh.reverses()) {
            if (loadedOnly) {
                pathLoaded(reverse.key());
            } else {
                pathFor(reverse.key());
            }
        }
        for (Consist train : this.trains.values()) {
            if (train.path().key().equals(key)) {
                train.rebased(train.path(), fresh);
            }
        }
    }

    // Logs the stops a rebaked path gained or lost, since a changed rail shape can send its trains another way.
    private static void warnIfRerouted(RailPath old, RailPath fresh) {
        Set<BlockPos> before = new LinkedHashSet<>();
        for (PathStation station : old.stations()) {
            before.add(station.pos());
        }
        Set<BlockPos> after = new LinkedHashSet<>();
        for (PathStation station : fresh.stations()) {
            after.add(station.pos());
        }
        Set<BlockPos> lost = new LinkedHashSet<>(before);
        lost.removeAll(after);
        Set<BlockPos> gained = new LinkedHashSet<>(after);
        gained.removeAll(before);
        if (!lost.isEmpty() || !gained.isEmpty()) {
            MmsMetro.LOGGER.warn("Path from {} changed stops on rebake: lost {}, gained {}",
                fresh.key().origin().toShortString(),
                lost,
                gained);
        }
    }

    // Forgets baked paths no train can reach any more.
    private void collectPaths() {
        Set<PathKey> keep = new HashSet<>();
        Deque<PathKey> queue = new ArrayDeque<>();
        for (Consist train : this.trains.values()) {
            queue.add(train.path().key());
        }
        while (!queue.isEmpty()) {
            PathKey key = queue.poll();
            if (!keep.add(key)) {
                continue;
            }
            RailPath path = this.paths.get(key);
            if (path != null) {
                for (PathReverse reverse : path.reverses()) {
                    queue.add(reverse.key());
                }
            }
        }
        this.paths.keySet().retainAll(keep);
        this.dirty.retainAll(keep);
    }

    private NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        NbtList pathList = new NbtList();
        for (RailPath path : this.paths.values()) {
            pathList.add(path.toNbt());
        }
        nbt.put("Paths", pathList);
        NbtList trainList = new NbtList();
        for (Consist train : this.trains.values()) {
            trainList.add(train.toNbt());
        }
        nbt.put("Trains", trainList);
        return nbt;
    }

    private static MetroState fromNbt(NbtCompound nbt) {
        MetroState state = new MetroState();
        NbtList pathList = nbt.getListOrEmpty("Paths");
        for (int i = 0; i < pathList.size(); i++) {
            RailPath path = RailPath.fromNbt(pathList.getCompoundOrEmpty(i));
            state.paths.put(path.key(), path);
        }
        NbtList trainList = nbt.getListOrEmpty("Trains");
        for (int i = 0; i < trainList.size(); i++) {
            NbtCompound trainNbt = trainList.getCompoundOrEmpty(i);
            RailPath path = state.paths.get(PathKey.fromNbt(trainNbt.getCompoundOrEmpty("Path")));
            if (path == null) {
                MmsMetro.LOGGER.warn("Dropping a saved train whose path is missing");
                continue;
            }
            try {
                UUID id = UUID.fromString(trainNbt.getString("Id", ""));
                state.trains.put(id, Consist.fromNbt(trainNbt, id, MmsMetro.config(), path));
            } catch (IllegalArgumentException e) {
                MmsMetro.LOGGER.warn("Dropping a saved train with a bad id");
            }
        }
        return state;
    }
}
