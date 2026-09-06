package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.block.SpeakerBlock;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.path.PathPoint;
import info.mudbourn.mmsmetro.path.PathStation;
import info.mudbourn.mmsmetro.path.RailPath;
import info.mudbourn.mmsmetro.registry.ModSounds;
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

// An ordered train of cars sharing one resolved path. The lead advances by
// arc-length, braking to a stop at each station, dwelling, then departing.
// Every follower sits a fixed distance behind the lead on that path.
public final class Consist {

    private enum Phase { RUNNING, DWELLING }

    // How far ahead of a stop the "incoming" cue fires, in blocks.
    private static final double INCOMING_DISTANCE = 12.0;

    // Radius, in blocks, to search around a station for a speaker block.
    private static final int SPEAKER_SEARCH_RADIUS = 5;

    private final List<MetroCarEntity> cars = new ArrayList<>();

    private RailPath path;

    private final MetroConfig config;

    private List<PathStation> stations;

    private List<info.mudbourn.mmsmetro.path.PathBump> bumps;

    private int nextBumpIndex;

    private double headArc;

    private double speed;

    private Phase phase = Phase.RUNNING;

    private int dwellTimer;

    private int nextStationIndex;

    private boolean incomingPlayed;

    // Set when the station currently being dwelt at is a terminus, so the train
    // turns around on departure instead of continuing.
    private boolean reverseAfterDwell;

    private int rollingTimer;

    // The train's live identity, shown on the onboard HUD. Set from the station
    // the train most recently served (or the first one ahead at spawn) and
    // carried until the next arrival changes it.
    private String currentLine = "";

    private String currentDirection = "";

    public Consist(RailPath path, MetroConfig config, double initialHeadArc) {
        this.path = path;
        this.config = config;
        this.headArc = Math.min(initialHeadArc, path.length());
        this.stations = path.stations();
        this.bumps = path.bumps();
        this.nextStationIndex = firstStationAhead(this.headArc);
        this.nextBumpIndex = firstBumpAhead(this.headArc);
        adoptIdentityFromNextStation();
    }

    // Seeds line/direction from the next station ahead so the HUD reads correctly
    // before the train has served its first stop.
    private void adoptIdentityFromNextStation() {
        PathStation next = nextStation();
        if (next != null) {
            this.currentLine = next.line();
            this.currentDirection = next.direction();
        }
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
            tickRunning();
        }

        applyCarPositions();
    }

    private void tickRunning() {
        PathStation target = nextStation();
        double targetArc = target != null ? target.arc() : this.path.length();
        double remaining = targetArc - this.headArc;

        if (!this.incomingPlayed && remaining <= INCOMING_DISTANCE && remaining > 0.0) {
            playFromLead(ModSounds.TRAIN_INCOMING, 1.0f);
            this.incomingPlayed = true;
        }

        // Brake once within stopping distance, otherwise accelerate to cruise.
        double brakingDistance = (this.speed * this.speed) / (2.0 * this.config.acceleration);
        if (remaining <= brakingDistance) {
            this.speed = Math.max(0.0, this.speed - this.config.acceleration);
        } else {
            this.speed = Math.min(this.config.maxSpeed, this.speed + this.config.acceleration);
        }

        this.headArc += this.speed;
        fireCrossedBumps();

        boolean stopped = this.headArc >= targetArc || (remaining <= brakingDistance && this.speed <= 1.0e-4);
        if (stopped) {
            this.headArc = targetArc;
            this.speed = 0.0;
            if (target != null) {
                arriveAt(target);
            } else {
                // Reached the end of the track with no station ahead: shuttle back.
                reverse();
            }
            return;
        }

        tickRolling();
    }

    private void arriveAt(PathStation station) {
        playArrival(station);
        playFromLead(ModSounds.BUFFER_WAIT, 0.8f);
        this.phase = Phase.DWELLING;
        this.dwellTimer = station.dwellTicks();
        this.reverseAfterDwell = station.terminus();
        // Serving this stop makes the train take on its line and direction.
        if (!station.line().isEmpty()) {
            this.currentLine = station.line();
        }
        if (!station.direction().isEmpty()) {
            this.currentDirection = station.direction();
        }
        // Reaching the stop clears any pending "arriving" announcement.
        setAnnouncement("");
    }

    private void tickDwell() {
        if (this.dwellTimer > 0) {
            this.dwellTimer--;
            return;
        }

        playFromLead(ModSounds.DEPARTURE, 1.0f);
        this.incomingPlayed = false;
        this.phase = Phase.RUNNING;
        if (this.reverseAfterDwell) {
            // The station commanded a turn-around: rebuild the path the other way.
            this.reverseAfterDwell = false;
            reverse();
        } else {
            this.nextStationIndex++;
        }
    }

    private void tickRolling() {
        if (this.speed <= 0.05) {
            return;
        }
        if (this.rollingTimer-- > 0) {
            return;
        }
        this.rollingTimer = 20;
        float pitch = 0.7f + (float) (this.speed / this.config.maxSpeed) * 0.5f;
        playFromLead(SoundEvents.ENTITY_MINECART_RIDING, 0.5f, pitch);
    }

    private void applyCarPositions() {
        PathStation next = nextStation();
        String nextName = next != null ? next.name() : "";
        boolean waiting = this.phase == Phase.DWELLING;
        for (int i = 0; i < this.cars.size(); i++) {
            double arc = Math.max(0.0, this.headArc - i * this.config.carSpacing);
            PathPoint point = this.path.sample(arc);
            MetroCarEntity car = this.cars.get(i);
            car.setPosition(point.pos().x, point.pos().y, point.pos().z);
            car.setYaw(point.yaw());
            car.setPitch(point.pitch());
            car.setPathYaw(point.yaw());
            car.setPathPitch(point.pitch());
            car.setVelocity(Vec3d.ZERO);
            car.setArcLength((float) arc);
            car.setHudInfo(this.currentLine, this.currentDirection, nextName, waiting);
        }
    }

    // Pushes an announcement string onto every car so all riders see it at once.
    private void setAnnouncement(String text) {
        for (MetroCarEntity car : this.cars) {
            car.setHudAnnouncement(text);
        }
    }

    // Fires the arrival announcement for every bump the head has just passed.
    private void fireCrossedBumps() {
        while (this.nextBumpIndex < this.bumps.size()
                && this.headArc >= this.bumps.get(this.nextBumpIndex).arc()) {
            setAnnouncement(buildAnnouncement(this.bumps.get(this.nextBumpIndex)));
            this.nextBumpIndex++;
        }
    }

    // The arrival line a bump announces, e.g.
    //   "Arriving at: Central, exit will be on the left. Transfer for Blue Line."
    //   "Arriving at: Depot, this is a terminal station. Exit on the right."
    private static String buildAnnouncement(info.mudbourn.mmsmetro.path.PathBump bump) {
        String name = bump.stationName().isEmpty() ? "the next station" : bump.stationName();
        String exit = bump.exitDirection();
        StringBuilder sb = new StringBuilder("Arriving at: ").append(name);
        if (bump.terminal()) {
            sb.append(", this is a terminal station.");
            if (!exit.isEmpty()) {
                sb.append(" Exit on the ").append(exit).append('.');
            }
        } else if (!exit.isEmpty()) {
            sb.append(", exit will be on the ").append(exit).append('.');
        } else {
            sb.append('.');
        }
        if (bump.hub() && !bump.transferLine().isEmpty()) {
            sb.append(" Transfer for ").append(bump.transferLine()).append('.');
        }
        return sb.toString();
    }

    private int firstBumpAhead(double arc) {
        for (int i = 0; i < this.bumps.size(); i++) {
            if (this.bumps.get(i).arc() > arc + 1.0e-3) {
                return i;
            }
        }
        return this.bumps.size();
    }

    // Turns the train around: rebuild the path from the current lead's rail
    // heading back the way it came, flip the car order so the old tail leads,
    // and re-seat every car onto the new path. Used at terminus stations and
    // at dead ends so a train shuttles instead of parking forever.
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
        this.stations = newPath.stations();
        this.bumps = newPath.bumps();
        this.headArc = Math.min((this.cars.size() - 1) * this.config.carSpacing, newPath.length());
        this.speed = 0.0;
        this.phase = Phase.RUNNING;
        this.incomingPlayed = false;
        this.nextStationIndex = firstStationAhead(this.headArc);
        this.nextBumpIndex = firstBumpAhead(this.headArc);
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

    private int firstStationAhead(double arc) {
        for (int i = 0; i < this.stations.size(); i++) {
            if (this.stations.get(i).arc() > arc + 1.0e-3) {
                return i;
            }
        }
        return this.stations.size();
    }

    private PathStation nextStation() {
        return this.nextStationIndex < this.stations.size()
            ? this.stations.get(this.nextStationIndex)
            : null;
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
