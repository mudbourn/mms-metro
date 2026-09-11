package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.block.SpeakerBlock;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.path.PathPoint;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// An ordered train of cars sharing one path: the lead advances by arc-length, stops and dwells at each station, followers trail at a fixed spacing.
public final class Consist {

    private enum Phase { RUNNING, DWELLING }

    // Radius, in blocks, to search around a station for a speaker block.
    private static final int SPEAKER_SEARCH_RADIUS = 5;

    private final List<MetroCarEntity> cars = new ArrayList<>();

    private RailPath path;

    private final MetroConfig config;

    private List<PathStation> stations;

    private List<info.mudbourn.mmsmetro.path.PathBump> bumps;

    // True when the path is a closed ring the train circles forever instead of shuttling back at the end of the station list.
    private boolean loop;

    // Arc through which bumps have already fired; a bump fires when the head crosses from at or below it to past it, so firing never depends on a cursor a marker refresh could desync.
    private double firedThroughArc;

    private double headArc;

    private double speed;

    private Phase phase = Phase.RUNNING;

    private int dwellTimer;

    // The dwell length resolved for the current stop, so a gated restart repeats it.
    private int currentDwellTicks;

    private int nextStationIndex;

    // Set when the current dwell is at a terminus, so the train turns around on departure instead of continuing.
    private boolean reverseAfterDwell;

    // Set once a bump for the upcoming station is crossed, so the train eases down from the bump to a smooth halt at the platform.
    private boolean approachBraking;

    // True after the head has crossed the ring seam at least once, so followers only wrap around the ring once the whole train has committed to it (never while the lead-in spur is still being ridden).
    private boolean ringCommitted;

    // Ticks the train holds after sounding its departure horn before it actually pulls out (3 seconds).
    private static final int DEPART_DELAY_TICKS = 60;

    // True while the departure horn has sounded and the train is counting down before it starts moving.
    private boolean departing;

    private int departTimer;

    private int rollingTimer;

    // Counts down between buffer-wait cues while held behind another train.
    private int bufferWaitTimer;

    // Fixed hold served when caught behind a train, in ticks (10 seconds).
    private static final int BUFFER_HOLD_TICKS = 200;

    // Ticks left of the forced hold behind a blocking train, served in full rather than resuming the instant the one ahead moves.
    private int bufferHold;

    // How often the path re-scans the world for markers, in ticks, so ones placed after spawn register without a respawn.
    private static final int MARKER_REFRESH_INTERVAL = 20;

    private int markerRefreshTimer;

    // The train's live identity for the onboard HUD, set from the last served station (or the first ahead at spawn) until the next arrival changes it.
    private String currentLine = "";

    private String currentDirection = "";

    // ARGB tint for the line name on the HUD, taken from the served station's line colour.
    private int currentLineColor = 0xFFF5A623;

    public Consist(RailPath path, MetroConfig config, double initialHeadArc) {
        this.path = path;
        this.config = config;
        this.headArc = Math.min(initialHeadArc, path.length());
        this.loop = path.isLoop();
        this.stations = path.stations();
        this.bumps = path.bumps();
        this.nextStationIndex = firstStationAhead(this.headArc);
        this.firedThroughArc = this.headArc;
        adoptIdentityFromNextStation();
    }

    // Seeds line/direction from the next station ahead so the HUD reads right before the train serves its first stop.
    private void adoptIdentityFromNextStation() {
        PathStation next = nextStation();
        if (next != null) {
            this.currentLine = next.line();
            this.currentDirection = next.direction();
            this.currentLineColor = lineColorArgb(next.lineColor());
        }
    }

    // Maps a DyeColor name to an opaque ARGB int for the HUD, defaulting to white for unknown names.
    private static int lineColorArgb(String name) {
        net.minecraft.util.DyeColor dye = net.minecraft.util.DyeColor.byId(name, net.minecraft.util.DyeColor.WHITE);
        return 0xFF000000 | (dye.getEntityColor() & 0xFFFFFF);
    }

    public void addCar(MetroCarEntity car) {
        this.cars.add(car);
    }

    public List<MetroCarEntity> cars() {
        return this.cars;
    }

    public void placeCars() {
        applyCarPositions();
    }

    public void tick() {
        if (this.path.length() <= 0.0) {
            return;
        }

        if (this.phase == Phase.DWELLING) {
            tickDwell();
        } else {
            if (--this.markerRefreshTimer <= 0) {
                this.markerRefreshTimer = MARKER_REFRESH_INTERVAL;
                refreshMarkers();
            }
            tickRunning();
        }

        applyCarPositions();
    }

    // Re-scans the track for markers and re-derives which lie ahead, so ones placed mid-run take effect next pass; only while moving, since a dwell must not disturb the indices.
    private void refreshMarkers() {
        ServerWorld world = leadWorld();
        if (world == null) {
            return;
        }
        this.path.refreshMarkers(world);
        this.stations = this.path.stations();
        this.bumps = this.path.bumps();
        this.nextStationIndex = firstStationAhead(this.headArc);
        // Adopt an upcoming line only if the train has not yet served a stop.
        if (this.currentLine.isEmpty()) {
            adoptIdentityFromNextStation();
        }
    }

    private void tickRunning() {
        // While serving the forced hold behind a train, stay put even if the one ahead has already pulled away.
        if (this.bufferHold > 0) {
            this.bufferHold--;
            this.speed = 0.0;
            return;
        }

        PathStation target = nextStation();
        double len = this.path.length();
        // On a loop, past the last station we cruise toward the seam with no station brake and fold the arc back to the ring start on reaching it.
        boolean wrap = wrappedTarget();
        double stationArc = wrap
            ? Double.POSITIVE_INFINITY
            : (target != null ? target.arc() : len);

        // A train ahead on our own path is a hard stop we hold behind, not a station; the nearer constraint decides where we brake.
        double blockerArc = blockingTrainHoldArc();
        boolean blockedByTrain = blockerArc < stationArc;
        double targetArc = Math.min(stationArc, blockerArc);
        double remaining = targetArc - this.headArc;

        // Hard stopping distance is the safety floor; a crossed bump starts an earlier, gentler ease-down to the platform.
        double brakingDistance = (this.speed * this.speed) / (2.0 * this.config.acceleration);
        boolean easeToStation = this.approachBraking && !blockedByTrain && target != null;
        if (remaining <= brakingDistance) {
            this.speed = Math.max(0.0, this.speed - this.config.acceleration);
        } else if (easeToStation) {
            // Decelerate just enough to reach zero at the station arc, never harder than a full brake.
            double stopDist = Math.max(1.0e-3, stationArc - this.headArc);
            double needed = (this.speed * this.speed) / (2.0 * stopDist);
            this.speed = Math.max(0.0, this.speed - Math.min(needed, this.config.acceleration));
        } else {
            this.speed = Math.min(this.config.maxSpeed, this.speed + this.config.acceleration);
        }

        this.headArc = Math.min(this.headArc + this.speed, targetArc);
        fireCrossedBumps();

        // Crossing the loop seam is not a stop: fold the arc back to the ring start and resume the station cycle without braking.
        if (wrap && !blockedByTrain && len > 0.0 && this.headArc >= len) {
            double back = this.path.loopStartArc();
            this.headArc = back + (this.headArc - len);
            this.ringCommitted = true;
            this.nextStationIndex = firstStationAtOrAfter(back);
            // The fold jumps the head past the seam, so fire any bumps sitting just after the ring start before resetting the fired mark.
            fireBumpsBetween(back, this.headArc);
            this.firedThroughArc = this.headArc;
            this.approachBraking = false;
            this.bufferWaitTimer = 0;
            tickRolling();
            return;
        }

        boolean stopped = this.headArc >= targetArc || (remaining <= brakingDistance && this.speed <= 1.0e-4);
        if (stopped) {
            this.headArc = targetArc;
            this.speed = 0.0;
            if (blockedByTrain) {
                // Caught behind another train: serve a fixed hold, sounding the cue once.
                if (this.bufferHold <= 0) {
                    playFromLead(ModSounds.BUFFER_WAIT, 0.8f);
                }
                this.bufferHold = BUFFER_HOLD_TICKS;
            } else if (target != null) {
                arriveAt(target);
            } else {
                // Reached the end of the track with no station ahead: shuttle back.
                reverse();
            }
            return;
        }

        this.bufferWaitTimer = 0;
        tickRolling();
    }

    // True when the train has served every station and is circling a loop back toward the first across the seam.
    private boolean wrappedTarget() {
        return this.loop && !this.stations.isEmpty()
            && this.nextStationIndex >= this.stations.size();
    }

    // Arc we must not pass because a train occupies the track ahead (keeping one car spacing clear), or +infinity when the line is clear within our headway.
    private double blockingTrainHoldArc() {
        ServerWorld world = leadWorld();
        if (world == null || this.cars.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        java.util.UUID myId = this.cars.get(0).getConsistId();
        double scanEnd = Math.min(this.path.length(), this.headArc + this.config.headway + this.config.carSpacing + this.config.maxSpeed);

        double nearestBlockerArc = Double.POSITIVE_INFINITY;
        for (MetroCarEntity other : world.getEntitiesByType(
                info.mudbourn.mmsmetro.registry.ModEntities.METRO_CAR, c -> true)) {
            if (other.isRemoved() || other.getConsistId().equals(myId)) {
                continue;
            }
            // Project the other car onto our path: the nearest sampled arc ahead within a car's width of it counts as on our line.
            for (double a = this.headArc + 0.5; a <= scanEnd; a += 1.0) {
                Vec3d p = this.path.sample(a).pos();
                if (p.squaredDistanceTo(other.getX(), other.getY(), other.getZ()) <= 2.25
                        && a < nearestBlockerArc) {
                    nearestBlockerArc = a;
                    break;
                }
            }
        }

        if (nearestBlockerArc == Double.POSITIVE_INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        // Hold a full headway behind the blocker, never closer than one car spacing.
        double gap = Math.max(this.config.headway, this.config.carSpacing);
        return Math.max(this.headArc, nearestBlockerArc - gap);
    }

    private void arriveAt(PathStation station) {
        playArrival(station);
        this.phase = Phase.DWELLING;
        // Fresh stop: clear any leftover departure countdown from the previous one.
        this.departing = false;
        this.departTimer = 0;
        // Served this stop: disarm the ease-down until the next bump arms it again.
        this.approachBraking = false;
        // A station's own dwell overrides the global default when it sets one.
        this.currentDwellTicks = station.dwellTicks() > 0 ? station.dwellTicks() : this.config.dwellTicks;
        this.dwellTimer = this.currentDwellTicks;
        this.reverseAfterDwell = station.terminus();
        // Serving this stop makes the train take on its line and direction.
        if (!station.line().isEmpty()) {
            this.currentLine = station.line();
        }
        if (!station.direction().isEmpty()) {
            this.currentDirection = station.direction();
        }
        this.currentLineColor = lineColorArgb(station.lineColor());
        // Stopped and dwelling: a boarding status while the train waits; the stop name is on the grey HUD line.
        setAnnouncement("The train will depart shortly.");
    }

    // The departing cue: the next stop's transfer if any, then the stay-seated warning. The next stop's name is left to the grey HUD line.
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
        // Counting down the post-horn hold: stay put until the delay elapses, then pull out.
        if (this.departing) {
            if (this.departTimer > 0) {
                this.departTimer--;
                return;
            }
            this.departing = false;
            this.phase = Phase.RUNNING;
            if (this.reverseAfterDwell) {
                // The station commanded a turn-around: rebuild the path the other way.
                this.reverseAfterDwell = false;
                reverse();
            } else {
                this.nextStationIndex++;
            }
            // Pulling out: announce the next stop, its transfer, and the track warning.
            setAnnouncement(buildDepartAnnouncement());
            return;
        }

        if (this.dwellTimer > 0) {
            this.dwellTimer--;
            return;
        }

        // Dwell elapsed: only depart if the train ahead has pulled far enough clear.
        if (!Double.isInfinite(blockingTrainHoldArc())) {
            // Still too close: restart the wait and re-sound the cue rather than departing into it.
            this.dwellTimer = this.currentDwellTicks;
            playFromLead(ModSounds.BUFFER_WAIT, 0.8f);
            return;
        }

        // Sound the departure horn, then hold DEPART_DELAY_TICKS before the train starts moving.
        playFromLead(ModSounds.DEPARTURE, 1.0f);
        this.departing = true;
        this.departTimer = DEPART_DELAY_TICKS;
    }

    private void tickRolling() {
        if (this.speed <= 0.05) {
            // Reset so the beat restarts in phase the moment the train moves again.
            this.rollingTimer = 0;
            return;
        }
        if (this.rollingTimer-- > 0) {
            return;
        }
        this.rollingTimer = 20;
        double speedRatio = Math.min(1.0, this.speed / this.config.maxSpeed);
        float pitch = 0.7f + (float) speedRatio * 0.5f;
        // Fade the volume with speed so the roar tapers to near-silence as the train brakes into a stop, instead of a full-volume instance lingering through the dwell.
        float volume = 0.5f * (float) speedRatio;
        ServerWorld world = leadWorld();
        if (world == null || this.cars.isEmpty()) {
            return;
        }
        // Play from the lead entity so the sound tracks the moving train instead of staying pinned to where it fired.
        world.playSoundFromEntity(null, this.cars.get(0), SoundEvents.ENTITY_MINECART_RIDING,
            SoundCategory.NEUTRAL, volume, pitch);
    }

    private void applyCarPositions() {
        PathStation next = nextStation();
        String nextName = next != null ? next.name() : "";
        boolean waiting = this.phase == Phase.DWELLING;
        boolean arriving = this.approachBraking && this.phase == Phase.RUNNING;
        double len = this.path.length();
        double back = this.path.loopStartArc();
        // Followers wrap behind the seam into the ring span only once the train has looped at least once (or the whole path is a ring); until then they clamp, so the tail never teleports onto the ring while the lead-in spur is still being ridden.
        boolean onRing = this.loop && len > 0.0 && (back == 0.0 || this.ringCommitted);
        for (int i = 0; i < this.cars.size(); i++) {
            double arc = this.headArc - i * this.config.carSpacing;
            if (onRing) {
                double ringLen = len - back;
                if (arc < back && ringLen > 0.0) {
                    arc = len - ((back - arc) % ringLen);
                }
            } else {
                arc = Math.max(0.0, arc);
            }
            PathPoint point = this.path.sample(arc);
            MetroCarEntity car = this.cars.get(i);
            Vec3d previous = car.getEntityPos();
            car.setPosition(point.pos().x, point.pos().y, point.pos().z);
            car.setYaw(point.yaw());
            car.setPitch(point.pitch());
            car.setPathYaw(point.yaw());
            car.setPathPitch(point.pitch());
            // Report this tick's movement as velocity so the client carries a seated rider along instead of leaving them a few ticks behind the teleported position.
            car.setVelocity(point.pos().subtract(previous));
            car.setArcLength((float) arc);
            car.setHudInfo(this.currentLine, this.currentDirection, nextName, waiting, this.currentLineColor, arriving);
            // Re-seat riders onto the car's new position this same tick, so entity tick order never leaves them a tick behind.
            for (Entity passenger : car.getPassengerList()) {
                car.updatePassengerPosition(passenger);
            }
        }
    }

    // Pushes an announcement string onto every car so all riders see it at once.
    private void setAnnouncement(String text) {
        for (MetroCarEntity car : this.cars) {
            car.setHudAnnouncement(text);
        }
    }

    // Fires the arrival announcement for every bump the head advanced across this tick and rings the upcoming station's speaker so waiting passengers hear the train approaching.
    private void fireCrossedBumps() {
        fireBumpsBetween(this.firedThroughArc, this.headArc);
        this.firedThroughArc = this.headArc;
    }

    // Fires every bump whose arc lies in (from, to]; scanning the whole list each tick means firing never depends on a cursor, so a mid-run marker refresh can never skip a bump.
    private void fireBumpsBetween(double from, double to) {
        if (to <= from) {
            return;
        }
        for (info.mudbourn.mmsmetro.path.PathBump bump : this.bumps) {
            double arc = bump.arc();
            if (arc <= from + 1.0e-6 || arc > to + 1.0e-6) {
                continue;
            }
            setAnnouncement(buildAnnouncement(bump));
            playIncomingAtStation(bump.stationPos());
            // Crossing the bump arms the ease-down for the station it heralds.
            this.approachBraking = true;
        }
    }

    // Plays the "train incoming" cue at the station's speaker (or the station block if none is nearby), so it sounds from the platform ahead rather than the moving train.
    private void playIncomingAtStation(BlockPos station) {
        ServerWorld world = leadWorld();
        if (world == null || station == null) {
            return;
        }
        BlockPos speaker = findSpeaker(world, station);
        BlockPos source = speaker != null ? speaker : station;
        world.playSound(null, source.getX() + 0.5, source.getY() + 0.5, source.getZ() + 0.5,
            ModSounds.TRAIN_INCOMING, SoundCategory.NEUTRAL, 1.0f, 1.0f);
    }

    // The approaching cue a bump announces, in future tense, showing where the exits will be. The stop name is carried by the grey HUD line.
    private static String buildAnnouncement(info.mudbourn.mmsmetro.path.PathBump bump) {
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

    // Turns the train around: rebuild the path back the way it came, flip the car order so the old tail leads, and re-seat every car; used at termini and dead ends so it shuttles instead of parking.
    private void reverse() {
        ServerWorld world = leadWorld();
        if (world == null || this.cars.isEmpty()) {
            this.speed = 0.0;
            return;
        }

        MetroCarEntity oldLead = this.cars.get(0);
        Direction newDir = horizontalFromYaw(oldLead.getPathYaw()).getOpposite();
        BlockPos startRail = ConsistManager.findRail(world,
            BlockPos.ofFloored(oldLead.getX(), oldLead.getY(), oldLead.getZ()));
        if (startRail == null) {
            this.speed = 0.0;
            return;
        }

        RailPath newPath = RailPath.build(world, startRail, newDir, RailPath.MAX_NODES);
        if (newPath.length() <= 0.0) {
            this.speed = 0.0;
            return;
        }

        Collections.reverse(this.cars);
        for (int i = 0; i < this.cars.size(); i++) {
            this.cars.get(i).setCarIndex(i);
        }

        this.path = newPath;
        this.loop = newPath.isLoop();
        this.stations = newPath.stations();
        this.bumps = newPath.bumps();
        this.headArc = Math.min((this.cars.size() - 1) * this.config.carSpacing, newPath.length());
        this.speed = 0.0;
        this.phase = Phase.RUNNING;
        this.approachBraking = false;
        this.ringCommitted = false;
        this.nextStationIndex = firstStationAhead(this.headArc);
        this.firedThroughArc = this.headArc;
        adoptIdentityFromNextStation();
        applyCarPositions();
    }

    // Nearest cardinal direction for a Minecraft yaw (0=south, 90=west, ...).
    private static Direction horizontalFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90.0f), 4)) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    // First station at or beyond the ring start, so a stop sitting on the seam is served each lap rather than skipped by a strict-ahead test.
    private int firstStationAtOrAfter(double arc) {
        for (int i = 0; i < this.stations.size(); i++) {
            if (this.stations.get(i).arc() >= arc - 1.0e-3) {
                return i;
            }
        }
        return this.stations.size();
    }

    private int firstStationAhead(double arc) {
        for (int i = 0; i < this.stations.size(); i++) {
            if (this.stations.get(i).arc() > arc + 1.0e-3) {
                return i;
            }
        }
        return this.stations.size();
    }

    private PathStation nextStation() {
        if (this.nextStationIndex < this.stations.size()) {
            return this.stations.get(this.nextStationIndex);
        }
        // Past the last stop on a loop, the next station is the first one again.
        if (this.loop && !this.stations.isEmpty()) {
            return this.stations.get(0);
        }
        return null;
    }

    private void playArrival(PathStation station) {
        ServerWorld world = leadWorld();
        if (world == null) {
            return;
        }

        BlockPos speaker = findSpeaker(world, station.pos());
        BlockPos source = speaker != null ? speaker : station.pos();
        world.playSound(null, source.getX() + 0.5, source.getY() + 0.5, source.getZ() + 0.5,
            ModSounds.ARRIVAL, SoundCategory.NEUTRAL, 1.0f, 1.0f);
    }

    // Nearest speaker block to a station, or null if none is placed nearby.
    private BlockPos findSpeaker(ServerWorld world, BlockPos station) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int r = SPEAKER_SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.iterate(station.add(-r, -r, -r), station.add(r, r, r))) {
            if (!(world.getBlockState(pos).getBlock() instanceof SpeakerBlock)) {
                continue;
            }
            double sq = pos.getSquaredDistance(station);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private void playFromLead(SoundEvent sound, float volume) {
        playFromLead(sound, volume, 1.0f);
    }

    private void playFromLead(SoundEvent sound, float volume, float pitch) {
        ServerWorld world = leadWorld();
        if (world == null || this.cars.isEmpty()) {
            return;
        }
        MetroCarEntity lead = this.cars.get(0);
        world.playSound(null, lead.getX(), lead.getY(), lead.getZ(),
            sound, SoundCategory.NEUTRAL, volume, pitch);
    }

    private ServerWorld leadWorld() {
        if (this.cars.isEmpty()) {
            return null;
        }
        return this.cars.get(0).getEntityWorld() instanceof ServerWorld world ? world : null;
    }

    public boolean isFinished() {
        for (MetroCarEntity car : this.cars) {
            if (!car.isRemoved()) {
                return false;
            }
        }
        return true;
    }

    public void discard() {
        for (MetroCarEntity car : this.cars) {
            car.discard();
        }
    }
}
