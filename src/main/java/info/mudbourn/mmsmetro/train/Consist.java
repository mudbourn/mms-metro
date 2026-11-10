package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.path.PathBump;
import info.mudbourn.mmsmetro.path.PathJunction;
import info.mudbourn.mmsmetro.path.PathPoint;
import info.mudbourn.mmsmetro.path.PathReverse;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModEntities;
import info.mudbourn.mmsmetro.registry.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// One train as saved data: the lead advances by arc-length along a baked path, stops and dwells at each station, and followers trail at a fixed spacing; car entities are views of it.
public final class Consist {

    private enum Phase { RUNNING, DWELLING }

    // How far ahead, in blocks, a train watches for a junction it may have to yield at.
    private static final double JUNCTION_SCAN = 48.0;

    // Distance, in blocks, a yielding train stops short of the junction node.
    private static final double JUNCTION_YIELD_GAP = 2.0;

    // Ticks the train holds after sounding its departure horn before it pulls out.
    private static final int DEPART_DELAY_TICKS = 60;

    // Fixed hold served when caught behind a train, in ticks.
    private static final int BUFFER_HOLD_TICKS = 200;

    // Slack, in blocks, added to the blocker scan window.
    private static final double BLOCKER_REACH_MARGIN = 4.0;

    // Upper bound on stops walked when predicting visits.
    private static final int VISIT_SEARCH_STOPS = 64;

    // Distance, in blocks, within which a station counts as the one a dwelling train stands at.
    private static final double STATION_REACH = 1.5;

    private final UUID id;

    private final MetroConfig config;

    private ServerWorld world;

    private MetroState state;

    private RailPath path;

    private int carCount;

    private MetroCarEntity[] views;

    // Chunk radius the car entity type is tracked within, matching its registered max tracking range.
    private static final int TRACKING_CHUNKS = 10;

    // Why the train is standing still this tick, or empty when it is moving or dwelling normally.
    private String waitReason = "";

    // True while a dwell has been extended because the track ahead is occupied.
    private boolean heldAtPlatform;

    // Consecutive ticks the train has stood waiting for the same kind of hold.
    private int stuckTicks;

    // Ticks left before a scheduled redraw fires, or zero when none is due.
    private int redrawIn;

    private boolean lost;

    private double firedThroughArc;

    private double headArc;

    private double speed;

    private Phase phase = Phase.RUNNING;

    private int dwellTimer;

    private int currentDwellTicks;

    private int nextStationIndex;

    private boolean reverseAfterDwell;

    private boolean approachBraking;

    private double easeStartArc;

    private double easeStartSpeed;

    private boolean ringCommitted;

    private boolean departing;

    private int departTimer;

    private int rollingTimer;

    private int bufferHold;

    private String currentLine = "";

    private String currentDirection = "";

    private int currentLineColor = 0xFFF5A623;

    private String announcement = "";

    public Consist(UUID id, MetroConfig config, RailPath path, double initialHeadArc, int carCount) {
        this.id = id;
        this.config = config;
        this.path = path;
        this.carCount = carCount;
        this.views = new MetroCarEntity[carCount];
        this.headArc = Math.min(initialHeadArc, path.length());
        this.nextStationIndex = firstStationAhead(this.headArc);
        this.firedThroughArc = this.headArc;
        adoptIdentityFromNextStation();
    }

    // Binds the train to the world and state it runs in.
    void attach(ServerWorld world, MetroState state) {
        this.world = world;
        this.state = state;
    }

    private void adoptIdentityFromNextStation() {
        adoptLineFromNextStation();
        applyTravelHeading();
    }

    private void adoptLineFromNextStation() {
        PathStation next = nextStation();
        if (next != null) {
            this.currentLine = next.line();
            this.currentLineColor = lineColorArgb(next.lineColor());
        }
    }

    private void applyTravelHeading() {
        String heading = this.path.headingName(this.headArc);
        if (!heading.isEmpty()) {
            this.currentDirection = heading;
        }
    }

    // Fixes the readout for the leg the train is pulling out onto.
    private void applyDepartureDirection(PathStation departed) {
        if (departed != null && !departed.direction().isEmpty()) {
            this.currentDirection = departed.direction();
            return;
        }
        applyTravelHeading();
    }

    // Maps a DyeColor name to an opaque ARGB int, defaulting to white.
    private static int lineColorArgb(String name) {
        DyeColor dye = DyeColor.byId(name, DyeColor.WHITE);
        return 0xFF000000 | (dye.getEntityColor() & 0xFFFFFF);
    }

    public UUID id() {
        return this.id;
    }

    public int carCount() {
        return this.carCount;
    }

    // Grows the train to at least this many cars.
    void ensureCars(int count) {
        if (count > this.carCount) {
            MetroCarEntity[] grown = new MetroCarEntity[count];
            System.arraycopy(this.views, 0, grown, 0, this.views.length);
            this.views = grown;
            this.carCount = count;
        }
    }

    RailPath path() {
        return this.path;
    }

    // The line the train currently serves.
    public String lineName() {
        return this.currentLine;
    }

    public double headArc() {
        return this.headArc;
    }

    void setHeadArc(double arc) {
        this.headArc = Math.min(arc, this.path.length());
        this.firedThroughArc = this.headArc;
        this.nextStationIndex = firstStationAhead(this.headArc);
    }

    boolean isDwelling() {
        return this.phase == Phase.DWELLING;
    }

    // The direction label the train currently shows.
    public String directionName() {
        return this.currentDirection;
    }

    // Length of the whole train along the track.
    private double trainLength() {
        return this.carCount * this.config.carSpacing;
    }

    private Vec3d leadPos() {
        return this.path.sample(this.headArc).pos();
    }

    // Squared distance from a point to the nearest car.
    double nearestCarSq(Vec3d point) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < this.carCount; i++) {
            best = Math.min(best, this.path.sample(carArc(i)).pos().squaredDistanceTo(point));
        }
        return best;
    }

    // True when any car sits within a box of the given half-size around a point.
    boolean anyCarWithin(Vec3d point, double clearance) {
        for (int i = 0; i < this.carCount; i++) {
            Vec3d pos = this.path.sample(carArc(i)).pos();
            if (Math.abs(pos.x - point.x) <= clearance
                && Math.abs(pos.y - point.y) <= clearance
                && Math.abs(pos.z - point.z) <= clearance) {
                return true;
            }
        }
        return false;
    }

    // One summary line for the list command.
    private int liveViews() {
        int live = 0;
        for (MetroCarEntity car : this.views) {
            if (car != null && !car.isRemoved()) {
                live++;
            }
        }
        return live;
    }

    public String describe() {
        return this.id.toString().substring(0, 8)
            + " line=" + (this.currentLine.isEmpty() ? "-" : this.currentLine)
            + " cars=" + liveViews() + "/" + this.carCount
            + " at " + BlockPos.ofFloored(this.path.sample(this.headArc).pos()).toShortString()
            + " " + this.phase.name().toLowerCase()
            + " arc=" + Math.round(this.headArc) + "/" + Math.round(this.path.length())
            + (this.lost ? " LOST (track removed under the train)" : "")
            + (stuckSeconds() > 0 ? " waiting " + stuckSeconds() + "s (" + this.waitReason + ")" : "");
    }

    public void tick() {
        if (this.lost || this.path.length() <= 0.0) {
            return;
        }
        this.waitReason = "";
        if (this.phase == Phase.DWELLING) {
            tickDwell();
        } else {
            tickRunning();
        }
        this.stuckTicks = this.waitReason.isEmpty() ? 0 : this.stuckTicks + 1;
    }

    // Seconds the train has stood held by another train or a junction, 0 when it is not held.
    public int stuckSeconds() {
        return this.stuckTicks / 20;
    }

    // What is holding the train, or empty when nothing is.
    public String waitReason() {
        return this.waitReason;
    }

    // The block the lead car stands in.
    public BlockPos headBlock() {
        return BlockPos.ofFloored(this.path.sample(this.headArc).pos());
    }

    // Marks the train stopped because its track no longer exists.
    void markLost() {
        this.lost = true;
        this.speed = 0.0;
    }

    // Moves the train onto a rebaked copy of its path, keeping the head on the same rail node.
    void rebased(RailPath old, RailPath fresh) {
        double mapped = fresh.arcMatching(old, this.headArc);
        if (Double.isNaN(mapped)) {
            markLost();
            return;
        }
        double shift = mapped - this.headArc;
        this.path = fresh;
        this.lost = false;
        this.headArc = mapped;
        this.easeStartArc += shift;
        this.firedThroughArc = mapped;
        this.nextStationIndex = this.phase == Phase.DWELLING
            ? stationIndexAt(mapped)
            : firstStationAhead(mapped);
        if (this.currentLine.isEmpty()) {
            adoptIdentityFromNextStation();
        }
    }

    private int stationIndexAt(double arc) {
        List<PathStation> stations = this.path.stations();
        for (int i = 0; i < stations.size(); i++) {
            if (Math.abs(stations.get(i).arc() - arc) <= STATION_REACH) {
                return i;
            }
        }
        return firstStationAhead(arc);
    }

    private void tickRunning() {
        if (this.bufferHold > 0) {
            this.bufferHold--;
            this.speed = 0.0;
            this.waitReason = "behind a train";
            return;
        }

        PathStation target = nextStation();
        double len = this.path.length();
        boolean wrap = wrappedTarget();
        double stationArc = wrap
            ? Double.POSITIVE_INFINITY
            : (target != null ? target.arc() : len);

        double blockerArc = blockingTrainHoldArc();
        double junctionArc = junctionYieldArc();
        double targetArc = Math.min(stationArc, Math.min(blockerArc, junctionArc));
        boolean blockedByTrain = blockerArc <= junctionArc && blockerArc < stationArc;
        boolean yieldingJunction = !blockedByTrain && junctionArc < stationArc;
        double remaining = targetArc - this.headArc;

        double brakingDistance = (this.speed * this.speed) / (2.0 * this.config.acceleration);
        boolean easeToStation = this.approachBraking && !blockedByTrain && !yieldingJunction && target != null;
        if (remaining <= brakingDistance) {
            this.speed = Math.max(0.0, this.speed - this.config.acceleration);
        } else if (easeToStation) {
            double span = Math.max(1.0e-3, stationArc - this.easeStartArc);
            double stopDist = Math.max(0.0, stationArc - this.headArc);
            double allowed = this.easeStartSpeed * Math.sqrt(stopDist / span);
            this.speed = Math.min(this.speed + this.config.acceleration, Math.min(this.config.maxSpeed, allowed));
        } else {
            this.speed = Math.min(this.config.maxSpeed, this.speed + this.config.acceleration);
        }

        this.headArc = Math.min(this.headArc + this.speed, targetArc);
        fireCrossedBumps();

        if (wrap && !blockedByTrain && !yieldingJunction && len > 0.0 && this.headArc >= len) {
            double back = this.path.loopStartArc();
            this.headArc = back + (this.headArc - len);
            this.ringCommitted = true;
            this.nextStationIndex = firstStationAtOrAfter(back);
            fireBumpsBetween(back, this.headArc);
            this.firedThroughArc = this.headArc;
            this.approachBraking = false;
            tickRolling();
            return;
        }

        boolean stopped = this.headArc >= targetArc || (remaining <= brakingDistance && this.speed <= 1.0e-4);
        if (stopped) {
            this.headArc = targetArc;
            this.speed = 0.0;
            if (blockedByTrain) {
                this.waitReason = "behind a train";
                if (this.bufferHold <= 0) {
                    playFromLead(ModSounds.BUFFER_WAIT, 0.8f);
                }
                this.bufferHold = BUFFER_HOLD_TICKS;
            } else if (yieldingJunction) {
                this.waitReason = "yielding at a junction";
            } else {
                if (target != null) {
                    arriveAt(target);
                } else {
                    reverse(false);
                }
            }
            return;
        }

        tickRolling();
    }

    // True when the train has served every station and is circling a loop back toward the first.
    private boolean wrappedTarget() {
        return this.path.isLoop() && !this.path.stations().isEmpty()
            && this.nextStationIndex >= this.path.stations().size();
    }

    // Arc we must not pass because a train occupies the track ahead, or +infinity when the line is clear within our headway.
    private double blockingTrainHoldArc() {
        double scanEnd = Math.min(this.path.length(), this.headArc + this.config.headway + this.config.carSpacing + this.config.maxSpeed);
        double reach = Math.max(0.0, scanEnd - this.headArc) + BLOCKER_REACH_MARGIN;
        Vec3d head = leadPos();

        double nearestBlockerArc = Double.POSITIVE_INFINITY;
        for (Consist other : this.state.trains()) {
            if (other == this) {
                continue;
            }
            double nearReach = reach + other.trainLength();
            if (other.leadPos().squaredDistanceTo(head) > nearReach * nearReach) {
                continue;
            }
            for (int i = 0; i < other.carCount; i++) {
                Vec3d pos = other.path.sample(other.carArc(i)).pos();
                if (pos.squaredDistanceTo(head) > reach * reach) {
                    continue;
                }
                for (double arc : this.path.arcsOnTrack(BlockPos.ofFloored(pos))) {
                    if (arc > this.headArc + 0.5 && arc <= scanEnd && arc < nearestBlockerArc) {
                        nearestBlockerArc = arc;
                    }
                }
            }
        }

        if (nearestBlockerArc == Double.POSITIVE_INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        double gap = Math.max(this.config.headway, this.config.carSpacing);
        return Math.max(this.headArc, nearestBlockerArc - gap);
    }

    // Arc we must not pass because a higher-priority train is crossing a junction ahead of us, or +infinity when none is contested.
    private double junctionYieldArc() {
        double hold = Double.POSITIVE_INFINITY;
        for (PathJunction junction : this.path.junctions()) {
            double myArc = junction.arc();
            if (myArc <= this.headArc + 0.5 || myArc - this.headArc > JUNCTION_SCAN) {
                continue;
            }
            if (junctionContested(junction)) {
                hold = Math.min(hold, Math.max(this.headArc, myArc - JUNCTION_YIELD_GAP));
            }
        }
        return hold;
    }

    // True when another train we must yield to is approaching or occupying this junction from a different heading.
    private boolean junctionContested(PathJunction junction) {
        String myHeading = this.path.headingName(junction.arc());
        for (Consist other : this.state.trains()) {
            if (other == this) {
                continue;
            }
            for (double otherArc : other.path.arcsOnTrack(junction.rail())) {
                double otherHead = other.headArc;
                if (otherArc <= otherHead - other.trainLength() - 0.5 || otherArc - otherHead > JUNCTION_SCAN) {
                    continue;
                }
                if (myHeading.equals(other.path.headingName(otherArc))) {
                    continue;
                }
                if (!hasJunctionPriority(other, junction)) {
                    return true;
                }
            }
        }
        return false;
    }

    // True when we outrank the other train at a junction: a dwelling train goes first, then the one nearer the junction's station, ties broken by id.
    private boolean hasJunctionPriority(Consist other, PathJunction junction) {
        if (this.isDwelling() != other.isDwelling()) {
            return this.isDwelling();
        }
        Vec3d target = junction.station() != null
            ? Vec3d.ofCenter(junction.station())
            : Vec3d.ofCenter(junction.rail());
        double myDist = leadPos().squaredDistanceTo(target);
        double otherDist = other.leadPos().squaredDistanceTo(target);
        if (Math.abs(myDist - otherDist) < 1.0e-3) {
            return this.id.compareTo(other.id) < 0;
        }
        return myDist < otherDist;
    }

    private void arriveAt(PathStation station) {
        SpeakerSound.play(this.world, station.speaker(), ModSounds.ARRIVAL);
        redrawIfUnseen();
        this.phase = Phase.DWELLING;
        this.departing = false;
        this.departTimer = 0;
        this.approachBraking = false;
        this.currentDwellTicks = station.dwellTicks() > 0 ? station.dwellTicks() : this.config.dwellTicks;
        this.dwellTimer = this.currentDwellTicks;
        this.reverseAfterDwell = station.terminus();
        if (!station.line().isEmpty()) {
            this.currentLine = station.line();
        }
        this.currentLineColor = lineColorArgb(station.lineColor());
        this.announcement = "The train will depart shortly.";
    }

    // The departing cue: the next stop's transfer if any, then the stay-seated warning.
    private String buildDepartAnnouncement() {
        PathStation next = nextStation();
        StringBuilder sb = new StringBuilder();
        if (next != null && next.hub() && !next.transferLine().isEmpty()) {
            sb.append("Transfer for ").append(next.transferLine()).append(". ");
        }
        sb.append("Please remain seated while the train is in motion.");
        return sb.toString();
    }

    private void tickDwell() {
        if (this.departing) {
            if (this.departTimer > 0) {
                this.departTimer--;
                return;
            }
            if (this.reverseAfterDwell) {
                if (!reverse(true)) {
                    return;
                }
                this.reverseAfterDwell = false;
            } else {
                this.nextStationIndex++;
            }
            this.departing = false;
            this.phase = Phase.RUNNING;
            this.announcement = buildDepartAnnouncement();
            return;
        }

        if (this.dwellTimer > 0) {
            this.dwellTimer--;
            if (this.heldAtPlatform) {
                this.waitReason = "held at the platform by a train ahead";
            }
            return;
        }

        if (!Double.isInfinite(blockingTrainHoldArc())) {
            this.heldAtPlatform = true;
            this.waitReason = "held at the platform by a train ahead";
            this.dwellTimer = this.currentDwellTicks;
            playFromLead(ModSounds.BUFFER_WAIT, 0.8f);
            return;
        }

        if (this.reverseAfterDwell && holdForInterval()) {
            return;
        }

        this.heldAtPlatform = false;
        if (this.reverseAfterDwell && nextStation() != null) {
            this.state.terminus(nextStation().pos()).depart(this.id, this.world.getTime());
        }
        playFromLead(ModSounds.DEPARTURE, 1.0f);
        applyDepartureDirection(nextStation());
        this.departing = true;
        this.departTimer = DEPART_DELAY_TICKS;
    }

    // True while the train waits at a terminus so departures from it stay an even share of the round trip apart.
    private boolean holdForInterval() {
        PathStation here = nextStation();
        if (here == null) {
            return false;
        }
        long wait = this.state.terminus(here.pos()).waitTicks(this.world.getTime(), trainsOnLine());
        if (wait <= 0L) {
            return false;
        }
        this.waitReason = "spacing out departures";
        return true;
    }

    // Trains serving the same line as this one, itself included.
    private int trainsOnLine() {
        int count = 0;
        for (Consist other : this.state.trains()) {
            if (other.currentLine.equalsIgnoreCase(this.currentLine)) {
                count++;
            }
        }
        return count;
    }

    private void tickRolling() {
        if (this.speed <= 0.05) {
            this.rollingTimer = 0;
            return;
        }
        if (this.rollingTimer-- > 0) {
            return;
        }
        this.rollingTimer = 20;
        double speedRatio = Math.min(1.0, this.speed / this.config.maxSpeed);
        float pitch = 0.7f + (float) speedRatio * 0.5f;
        float volume = 0.5f * (float) speedRatio;
        MetroCarEntity lead = this.views[0];
        if (lead == null || lead.isRemoved()) {
            return;
        }
        this.world.playSoundFromEntity(null, lead, SoundEvents.ENTITY_MINECART_RIDING,
            SoundCategory.NEUTRAL, volume, pitch);
    }

    // Arc of one car, trailing the head and wrapping behind the loop seam once the train has committed to the ring.
    private double carArc(int index) {
        double len = this.path.length();
        double back = this.path.loopStartArc();
        boolean onRing = this.path.isLoop() && len > 0.0 && (back == 0.0 || this.ringCommitted);
        double arc = this.headArc - index * this.config.carSpacing;
        if (onRing) {
            double ringLen = len - back;
            if (arc < back && ringLen > 0.0) {
                arc = len - ((back - arc) % ringLen);
            }
            return arc;
        }
        return Math.max(0.0, arc);
    }

    // Makes each car entity exist exactly where its chunk has entities loaded, and seats it on the path.
    public void syncViews() {
        PathStation next = nextStation();
        String nextName = next != null ? next.name() : "";
        boolean waiting = this.phase == Phase.DWELLING;
        boolean arriving = this.approachBraking && this.phase == Phase.RUNNING;
        for (int i = 0; i < this.carCount; i++) {
            double arc = carArc(i);
            PathPoint point = this.path.sample(arc);
            MetroCarEntity car = this.views[i];
            if (car != null && car.isRemoved()) {
                car = null;
                this.views[i] = null;
            }
            boolean active = this.world.isChunkLoaded(ChunkPos.toLong(BlockPos.ofFloored(point.pos())));
            if (!active && (car == null || !car.hasPlayerRider())) {
                if (car != null) {
                    despawn(car);
                    this.views[i] = null;
                }
                continue;
            }
            if (car == null) {
                car = spawnView(i, point, arc);
                if (car == null) {
                    continue;
                }
                this.views[i] = car;
            }
            car.setPosition(point.pos().x, point.pos().y, point.pos().z);
            car.setYaw(point.yaw());
            car.setPitch(point.pitch());
            car.setPathYaw(point.yaw());
            car.setPathPitch(point.pitch());
            car.setTrackedPos(point.pos().x, point.pos().y, point.pos().z);
            car.setArcLength((float) arc);
            car.setCarCount(this.carCount);
            car.setHudInfo(this.currentLine, this.currentDirection, nextName, waiting, this.currentLineColor, arriving);
            car.setHudAnnouncement(this.announcement);
            for (Entity passenger : car.getPassengerList()) {
                car.updatePassengerPosition(passenger);
            }
        }
    }

    // Adds the chunk of every car to the set.
    void collectCarChunks(it.unimi.dsi.fastutil.longs.LongSet out) {
        for (int i = 0; i < this.carCount; i++) {
            out.add(new net.minecraft.util.math.ChunkPos(BlockPos.ofFloored(this.path.sample(carArc(i)).pos())).toLong());
        }
    }

    // Spawns a car with its path position already published, so the spawn packet carries it.
    private MetroCarEntity spawnView(int index, PathPoint point, double arc) {
        MetroCarEntity car = new MetroCarEntity(ModEntities.METRO_CAR, this.world);
        car.markView();
        car.setConsistId(this.id);
        car.setCarIndex(index);
        car.setCarCount(this.carCount);
        car.setPosition(point.pos().x, point.pos().y, point.pos().z);
        car.setYaw(point.yaw());
        car.setPitch(point.pitch());
        car.setPathYaw(point.yaw());
        car.setPathPitch(point.pitch());
        car.setTrackedPos(point.pos().x, point.pos().y, point.pos().z);
        car.setArcLength((float) arc);
        return this.world.spawnEntity(car) ? car : null;
    }

    // Removes a view, leaving any non-player rider standing where it is.
    private static void despawn(MetroCarEntity car) {
        dismountMobs(car);
        car.discard();
    }

    private static void dismountMobs(MetroCarEntity car) {
        for (Entity passenger : new ArrayList<>(car.getPassengerList())) {
            if (!(passenger instanceof PlayerEntity)) {
                passenger.stopRiding();
            }
        }
    }

    // Dismounts non-player riders from every view and removes the views.
    void despawnViews() {
        for (int i = 0; i < this.views.length; i++) {
            if (this.views[i] != null) {
                despawn(this.views[i]);
                this.views[i] = null;
            }
        }
    }

    // True when every car sits in a chunk within the given chunk distance of a position.
    boolean allCarsWithinChunks(Vec3d point, int chunks) {
        int px = BlockPos.ofFloored(point).getX() >> 4;
        int pz = BlockPos.ofFloored(point).getZ() >> 4;
        for (int i = 0; i < this.carCount; i++) {
            BlockPos car = BlockPos.ofFloored(this.path.sample(carArc(i)).pos());
            if (Math.max(Math.abs((car.getX() >> 4) - px), Math.abs((car.getZ() >> 4) - pz)) > chunks) {
                return false;
            }
        }
        return true;
    }

    // Schedules a redraw after the given number of ticks, keeping any sooner one.
    void scheduleRedraw(int ticks) {
        this.redrawIn = this.redrawIn > 0 ? Math.min(this.redrawIn, ticks) : ticks;
    }

    // Counts down a scheduled redraw and fires it once due if a player is within the given chunk distance of a car.
    void tickRedraw(int chunks) {
        if (this.redrawIn <= 0 || --this.redrawIn > 0) {
            return;
        }
        for (PlayerEntity player : this.world.getPlayers()) {
            if (anyCarWithinChunks(player.getEntityPos(), chunks)) {
                redraw();
                return;
            }
        }
    }

    // True when any car sits in a chunk within the given chunk distance of a position.
    boolean anyCarWithinChunks(Vec3d point, int chunks) {
        int px = BlockPos.ofFloored(point).getX() >> 4;
        int pz = BlockPos.ofFloored(point).getZ() >> 4;
        for (int i = 0; i < this.carCount; i++) {
            BlockPos car = BlockPos.ofFloored(this.path.sample(carArc(i)).pos());
            if (Math.max(Math.abs((car.getX() >> 4) - px), Math.abs((car.getZ() >> 4) - pz)) <= chunks) {
                return true;
            }
        }
        return false;
    }

    // Drops every view without a player rider so the next sync respawns it.
    void redraw() {
        for (int i = 0; i < this.views.length; i++) {
            MetroCarEntity car = this.views[i];
            if (car != null && !car.hasPlayerRider()) {
                despawn(car);
                this.views[i] = null;
            }
        }
    }

    // Dismounts non-player riders from every view, keeping the views.
    void dismountMobs() {
        for (MetroCarEntity car : this.views) {
            if (car != null) {
                dismountMobs(car);
            }
        }
    }

    // Removes every live view.
    void discardViews() {
        for (int i = 0; i < this.views.length; i++) {
            if (this.views[i] != null) {
                this.views[i].discard();
                this.views[i] = null;
            }
        }
    }

    // Fires the arrival announcement for every bump the head advanced across this tick.
    private void fireCrossedBumps() {
        fireBumpsBetween(this.firedThroughArc, this.headArc);
        this.firedThroughArc = this.headArc;
    }

    // Fires every bump whose arc lies in (from, to].
    private void fireBumpsBetween(double from, double to) {
        if (to <= from) {
            return;
        }
        for (PathBump bump : this.path.bumps()) {
            double arc = bump.arc();
            if (arc <= from + 1.0e-6 || arc > to + 1.0e-6) {
                continue;
            }
            this.announcement = buildAnnouncement(bump);
            SpeakerSound.play(this.world, bump.speaker(), ModSounds.TRAIN_INCOMING);
            redrawIfUnseen();
            if (!this.approachBraking) {
                this.easeStartArc = this.headArc;
                this.easeStartSpeed = Math.max(this.speed, this.config.acceleration);
            }
            this.approachBraking = true;
        }
    }

    // The approaching cue a bump announces, showing where the exits will be.
    private static String buildAnnouncement(PathBump bump) {
        String exit = bump.exitDirection();
        StringBuilder sb = new StringBuilder();
        if (bump.terminal()) {
            sb.append("This is a terminal station.");
            if (!exit.isEmpty()) {
                sb.append(" Exit will be on the ").append(exit).append('.');
            }
        } else if (!exit.isEmpty()) {
            sb.append("Exit will be on the ").append(exit).append('.');
        }
        return sb.toString();
    }

    // Turns the train around onto the baked path leading back from its turn-around point, flipping the car order so the old tail leads.
    private boolean reverse(boolean keepDirection) {
        PathReverse turn = this.path.reverseAt(this.headArc);
        RailPath next = turn == null ? null : this.state.pathFor(turn.key());
        if (next == null) {
            this.speed = 0.0;
            return false;
        }

        for (int i = 0; i < this.views.length / 2; i++) {
            MetroCarEntity swap = this.views[i];
            this.views[i] = this.views[this.views.length - 1 - i];
            this.views[this.views.length - 1 - i] = swap;
        }
        for (int i = 0; i < this.views.length; i++) {
            if (this.views[i] != null) {
                this.views[i].setCarIndex(i);
            }
        }

        this.path = next;
        this.headArc = turnStartArc(next);
        this.speed = 0.0;
        this.phase = Phase.RUNNING;
        this.approachBraking = false;
        this.ringCommitted = false;
        this.nextStationIndex = firstStationAhead(this.headArc);
        this.firedThroughArc = this.headArc;
        if (keepDirection) {
            adoptLineFromNextStation();
        } else {
            adoptIdentityFromNextStation();
        }
        return true;
    }

    // First station at or beyond an arc, so a stop on the loop seam is served each lap.
    private int firstStationAtOrAfter(double arc) {
        List<PathStation> stations = this.path.stations();
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).arc() >= arc - 1.0e-3) {
                return i;
            }
        }
        return stations.size();
    }

    private int firstStationAhead(double arc) {
        List<PathStation> stations = this.path.stations();
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).arc() > arc + 1.0e-3) {
                return i;
            }
        }
        return stations.size();
    }

    // One predicted call at a station: ticks from now until arrival, the direction shown then, the terminal it runs to, and whether the train is already there.
    public record Visit(double ticks, String direction, String terminal, boolean atPlatform) {
    }

    // Predicts the next calls at a station block by walking the route, timing each run from stop to stop with acceleration and braking, and counting dwells, loop laps and turn-arounds onto baked reverse paths.
    public List<Visit> upcomingVisits(BlockPos stationPos, int max) {
        List<Visit> visits = new ArrayList<>();
        RailPath walk = this.path;
        double x = this.headArc;
        int dir = 1;
        double ticks = 0.0;
        double run = 0.0;
        double startSpeed = this.speed;
        String label = this.currentDirection;
        PathStation here = nextStation();
        if (this.phase == Phase.DWELLING && here != null) {
            if (here.pos().equals(stationPos)) {
                visits.add(new Visit(0.0, label, terminalFrom(walk, here, 1), true));
            }
            ticks = ticksUntilDeparture();
            startSpeed = 0.0;
            if (!here.direction().isEmpty()) {
                label = here.direction();
            }
            x = here.arc();
            if (here.terminus()) {
                RailPath turned = turnedPath(walk, x);
                if (turned != null) {
                    walk = turned;
                    x = turnStartArc(turned);
                } else {
                    dir = -1;
                }
            }
        }
        for (int step = 0; step < VISIT_SEARCH_STOPS && visits.size() < max; step++) {
            double len = walk.length();
            PathStation stop = nextStopFrom(walk, x, dir);
            if (stop == null) {
                if (walk.isLoop() && dir > 0) {
                    run += len - x;
                    x = walk.loopStartArc() - 1.0e-3;
                    if (nextStopFrom(walk, x, dir) == null) {
                        break;
                    }
                    continue;
                }
                double end = dir > 0 ? len : 0.0;
                ticks += runTicks(run + Math.abs(end - x), startSpeed, this.config);
                run = 0.0;
                startSpeed = 0.0;
                RailPath turned = dir > 0 ? turnedPath(walk, end) : null;
                if (turned != null) {
                    walk = turned;
                    x = turnStartArc(turned);
                } else {
                    x = end;
                    dir = -dir;
                }
                if (nextStopFrom(walk, x, dir) == null) {
                    break;
                }
                continue;
            }
            ticks += runTicks(run + Math.abs(stop.arc() - x), startSpeed, this.config);
            run = 0.0;
            startSpeed = 0.0;
            x = stop.arc();
            if (stop.pos().equals(stationPos)) {
                visits.add(new Visit(ticks, label, terminalFrom(walk, stop, dir), false));
            }
            ticks += (stop.dwellTicks() > 0 ? stop.dwellTicks() : this.config.dwellTicks) + DEPART_DELAY_TICKS;
            if (!stop.direction().isEmpty()) {
                label = stop.direction();
            }
            if (stop.terminus()) {
                RailPath turned = dir > 0 ? turnedPath(walk, x) : null;
                if (turned != null) {
                    walk = turned;
                    x = turnStartArc(turned);
                } else {
                    dir = -dir;
                }
            }
        }
        return visits;
    }

    // Ticks to cover a distance from a starting speed and stop at its end, accelerating to top speed and braking at the configured rate.
    private static double runTicks(double distance, double fromSpeed, MetroConfig config) {
        double accel = Math.max(1.0e-4, config.acceleration);
        double top = Math.max(1.0e-3, config.maxSpeed);
        double v0 = Math.min(fromSpeed, top);
        double speedUp = (top * top - v0 * v0) / (2.0 * accel);
        double slowDown = top * top / (2.0 * accel);
        if (distance >= speedUp + slowDown) {
            return (top - v0) / accel + top / accel + (distance - speedUp - slowDown) / top;
        }
        double peak = Math.sqrt((2.0 * accel * distance + v0 * v0) / 2.0);
        if (peak <= v0) {
            return v0 > 0.0 ? 2.0 * distance / v0 : 0.0;
        }
        return (peak - v0) / accel + peak / accel;
    }

    // The already-baked path a train turning around at an arc continues on, or null when none is held.
    private RailPath turnedPath(RailPath from, double arc) {
        PathReverse turn = from.reverseAt(arc);
        return turn == null || this.state == null ? null : this.state.bakedPath(turn.key());
    }

    // Head arc a train starts from after turning onto a path, matching reverse().
    private double turnStartArc(RailPath next) {
        return Math.min((this.carCount - 1) * this.config.carSpacing, next.length());
    }

    // Name of the stop a train leaving a station runs to: the first terminus beyond it, else the farthest stop that way, else the station itself.
    private static String terminalFrom(RailPath walk, PathStation from, int dir) {
        PathStation end = from;
        double x = from.arc();
        PathStation stop = nextStopFrom(walk, x, dir);
        while (stop != null) {
            end = stop;
            if (stop.terminus()) {
                break;
            }
            x = stop.arc();
            stop = nextStopFrom(walk, x, dir);
        }
        return end.name();
    }

    // Nearest station strictly beyond an arc in a travel direction, or null when none lies that way.
    private static PathStation nextStopFrom(RailPath walk, double x, int dir) {
        PathStation best = null;
        for (PathStation station : walk.stations()) {
            boolean ahead = dir > 0 ? station.arc() > x + 1.0e-3 : station.arc() < x - 1.0e-3;
            if (ahead && (best == null || Math.abs(station.arc() - x) < Math.abs(best.arc() - x))) {
                best = station;
            }
        }
        return best;
    }

    // Ticks until the train pulls out of its current stop, 0 when it is not dwelling.
    public int ticksUntilDeparture() {
        if (this.phase != Phase.DWELLING) {
            return 0;
        }
        return this.departing ? this.departTimer : this.dwellTimer + DEPART_DELAY_TICKS;
    }

    // True while the train is running between stops rather than dwelling at one.
    boolean isRunning() {
        return this.phase == Phase.RUNNING;
    }

    // Names of the stop the train is at or running to and the stop after it on its path, empty where there is none.
    String[] nextTwoStops() {
        List<PathStation> stations = this.path.stations();
        int i = this.nextStationIndex;
        String first = i < stations.size() ? stations.get(i).name() : "";
        String second = i + 1 < stations.size() ? stations.get(i + 1).name() : "";
        return new String[]{first, second};
    }

    private PathStation nextStation() {
        List<PathStation> stations = this.path.stations();
        if (this.nextStationIndex < stations.size()) {
            return stations.get(this.nextStationIndex);
        }
        if (this.path.isLoop() && !stations.isEmpty()) {
            return stations.get(0);
        }
        return null;
    }

    private void playFromLead(SoundEvent sound, float volume) {
        Vec3d pos = leadPos();
        if (!this.world.isPosLoaded(BlockPos.ofFloored(pos))) {
            return;
        }
        this.world.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.NEUTRAL, volume, 1.0f);
        redrawIfUnseen();
    }

    // Redraws the train when a player is not tracking a live car that sits within both the simulation and tracking distance of them.
    private void redrawIfUnseen() {
        int chunks = Math.min(this.world.getServer().getPlayerManager().getSimulationDistance(), TRACKING_CHUNKS);
        for (ServerPlayerEntity player : this.world.getPlayers()) {
            ChunkPos at = player.getChunkPos();
            for (MetroCarEntity car : this.views) {
                if (car == null || car.isRemoved()) {
                    continue;
                }
                ChunkPos carAt = car.getChunkPos();
                boolean near = Math.max(Math.abs(carAt.x - at.x), Math.abs(carAt.z - at.z)) <= chunks;
                if (near && !PlayerLookup.tracking(car).contains(player)) {
                    redraw();
                    return;
                }
            }
        }
    }

    // Serializes the train's motion and display state.
    NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Id", this.id.toString());
        nbt.put("Path", this.path.key().toNbt());
        nbt.putInt("Cars", this.carCount);
        nbt.putBoolean("Lost", this.lost);
        nbt.putDouble("HeadArc", this.headArc);
        nbt.putDouble("Speed", this.speed);
        nbt.putInt("Phase", this.phase.ordinal());
        nbt.putInt("DwellTimer", this.dwellTimer);
        nbt.putInt("DwellTicks", this.currentDwellTicks);
        nbt.putInt("NextStation", this.nextStationIndex);
        nbt.putDouble("FiredThrough", this.firedThroughArc);
        nbt.putBoolean("ReverseAfterDwell", this.reverseAfterDwell);
        nbt.putBoolean("ApproachBraking", this.approachBraking);
        nbt.putDouble("EaseStartArc", this.easeStartArc);
        nbt.putDouble("EaseStartSpeed", this.easeStartSpeed);
        nbt.putBoolean("RingCommitted", this.ringCommitted);
        nbt.putBoolean("Departing", this.departing);
        nbt.putInt("DepartTimer", this.departTimer);
        nbt.putInt("BufferHold", this.bufferHold);
        nbt.putString("Line", this.currentLine);
        nbt.putString("Direction", this.currentDirection);
        nbt.putInt("LineColor", this.currentLineColor);
        nbt.putString("Announcement", this.announcement);
        return nbt;
    }

    // Rebuilds a train from saved data on its baked path.
    static Consist fromNbt(NbtCompound nbt, UUID id, MetroConfig config, RailPath path) {
        Consist consist = new Consist(id, config, path, nbt.getDouble("HeadArc", 0.0), nbt.getInt("Cars", 1));
        consist.lost = nbt.getBoolean("Lost", false);
        consist.speed = nbt.getDouble("Speed", 0.0);
        int phase = nbt.getInt("Phase", 0);
        consist.phase = phase >= 0 && phase < Phase.values().length ? Phase.values()[phase] : Phase.RUNNING;
        consist.dwellTimer = nbt.getInt("DwellTimer", 0);
        consist.currentDwellTicks = nbt.getInt("DwellTicks", 0);
        consist.nextStationIndex = nbt.getInt("NextStation", 0);
        consist.firedThroughArc = nbt.getDouble("FiredThrough", consist.headArc);
        consist.reverseAfterDwell = nbt.getBoolean("ReverseAfterDwell", false);
        consist.approachBraking = nbt.getBoolean("ApproachBraking", false);
        consist.easeStartArc = nbt.getDouble("EaseStartArc", 0.0);
        consist.easeStartSpeed = nbt.getDouble("EaseStartSpeed", 0.0);
        consist.ringCommitted = nbt.getBoolean("RingCommitted", false);
        consist.departing = nbt.getBoolean("Departing", false);
        consist.departTimer = nbt.getInt("DepartTimer", 0);
        consist.bufferHold = nbt.getInt("BufferHold", 0);
        consist.currentLine = nbt.getString("Line", "");
        consist.currentDirection = nbt.getString("Direction", "");
        consist.currentLineColor = nbt.getInt("LineColor", 0xFFF5A623);
        consist.announcement = nbt.getString("Announcement", "");
        return consist;
    }
}
