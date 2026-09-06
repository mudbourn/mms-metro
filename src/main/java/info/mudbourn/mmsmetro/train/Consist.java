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
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
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

    private final RailPath path;

    private final MetroConfig config;

    private final List<PathStation> stations;

    private double headArc;

    private double speed;

    private Phase phase = Phase.RUNNING;

    private int dwellTimer;

    private int nextStationIndex;

    private boolean incomingPlayed;

    private int rollingTimer;

    public Consist(RailPath path, MetroConfig config, double initialHeadArc) {
        this.path = path;
        this.config = config;
        this.headArc = Math.min(initialHeadArc, path.length());
        this.stations = path.stations();
        this.nextStationIndex = firstStationAhead(this.headArc);
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

        boolean stopped = this.headArc >= targetArc || (remaining <= brakingDistance && this.speed <= 1.0e-4);
        if (stopped) {
            this.headArc = targetArc;
            this.speed = 0.0;
            if (target != null) {
                arriveAt(target);
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
    }

    private void tickDwell() {
        if (this.dwellTimer > 0) {
            this.dwellTimer--;
            return;
        }

        playFromLead(ModSounds.DEPARTURE, 1.0f);
        this.incomingPlayed = false;
        this.nextStationIndex++;
        this.phase = Phase.RUNNING;
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
        }
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
