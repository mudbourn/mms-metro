package info.mudbourn.mmsmetro.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// A train car: a pure display entity driven by the consist, not vanilla physics.
public class MetroCarEntity extends Entity {

    // Position along the resolved path in blocks, from the lead's origin.
    private static final TrackedData<Float> ARC_LENGTH =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Zero for the lead car, increasing toward the tail.
    private static final TrackedData<Integer> CAR_INDEX =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Path-derived orientation, tracked explicitly so the client rotates the model (vanilla rotation sync is unreliable for a teleport-driven entity).
    private static final TrackedData<Float> PATH_YAW =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> PATH_PITCH =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Onboard-HUD fields, stamped onto every car each tick so any rider reads the same live line, direction, and next stop; announcement carries the transient "Arriving at..." cue.
    private static final TrackedData<String> HUD_LINE =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.STRING);

    private static final TrackedData<String> HUD_DIRECTION =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.STRING);

    private static final TrackedData<String> HUD_NEXT_STATION =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.STRING);

    private static final TrackedData<String> HUD_ANNOUNCEMENT =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.STRING);

    private static final TrackedData<Boolean> HUD_WAITING =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // ARGB colour the HUD tints the line name with, taken from the served station's line colour.
    private static final TrackedData<Integer> HUD_LINE_COLOR =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // True once the train has crossed the approach bump and is braking in, so the HUD reads "Arriving at" rather than "Next stop".
    private static final TrackedData<Boolean> HUD_ARRIVING =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // The consist id as a string, tracked so the client can group a train's cars even after a reload, when the in-memory registry is gone.
    private static final TrackedData<String> CONSIST_ID =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.STRING);

    // Path position carried through the DataTracker, the one channel that reaches the client every tick even when vanilla suppresses movement packets for a car near a ridden vehicle; the client drives the body from these rather than from entity-movement packets.
    private static final TrackedData<Float> POS_X =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> POS_Y =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> POS_Z =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Client-side interpolation of the tracked orientation.
    private float prevPathYaw;
    private float prevPathPitch;

    // The arc seen on the client last tick, so a discontinuous jump (a terminus reverse or a chunk-reload teleport) can be snapped rather than eased across.
    private float lastClientArc;

    // An arc step larger than this between ticks is a rewrite of the path, not motion, so the car hard-snaps to its new pose.
    private static final float ARC_DISCONTINUITY = 8.0f;

    // Squared block distance past which the body is snapped straight to its synced position: far beyond one tick of legitimate interpolation lag, so it only fires when the interpolator has wedged and stranded a car off the rail.
    private static final double POS_RESYNC_SQ = 16.0;

    // Smooths the car's position between the consist's per-tick teleports so the body and its riders advance together over the tracking interval instead of the rider trailing the car.
    private final PositionInterpolator interpolator = new PositionInterpolator(this, 1);

    // Groups the cars of one train so a car can be traced to its whole consist even after a reload, when the in-memory registry is gone.
    private java.util.UUID consistId = java.util.UUID.randomUUID();

    // The rail and heading the consist's path was walked from, persisted so a reload rebuilds the identical path deterministically rather than re-guessing one that diverges at junctions; null until the consist stamps it.
    private net.minecraft.util.math.BlockPos pathOrigin;

    private net.minecraft.util.math.Direction pathInitialDir;

    public MetroCarEntity(EntityType<? extends MetroCarEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(ARC_LENGTH, 0.0f);
        builder.add(CAR_INDEX, 0);
        builder.add(PATH_YAW, 0.0f);
        builder.add(PATH_PITCH, 0.0f);
        builder.add(HUD_LINE, "");
        builder.add(HUD_DIRECTION, "");
        builder.add(HUD_NEXT_STATION, "");
        builder.add(HUD_ANNOUNCEMENT, "");
        builder.add(HUD_WAITING, false);
        builder.add(HUD_LINE_COLOR, 0xFFF5A623);
        builder.add(HUD_ARRIVING, false);
        builder.add(CONSIST_ID, "");
        builder.add(POS_X, 0.0f);
        builder.add(POS_Y, 0.0f);
        builder.add(POS_Z, 0.0f);
    }

    // Server-side: publishes this tick's path position onto the DataTracker so every tracking client can drive the body from it.
    public void setTrackedPos(double x, double y, double z) {
        this.dataTracker.set(POS_X, (float) x);
        this.dataTracker.set(POS_Y, (float) y);
        this.dataTracker.set(POS_Z, (float) z);
    }

    private Vec3d trackedPos() {
        return new Vec3d(this.dataTracker.get(POS_X), this.dataTracker.get(POS_Y), this.dataTracker.get(POS_Z));
    }

    // Exposes the raw synced path position so the scan can tell a frozen tracker (a sync drop) from a tracker that updates while the entity stays put (an interpolator wedge).
    public Vec3d trackedPosDebug() {
        return trackedPos();
    }

    // Mirrors the synced consist id onto the client so its cars group even after a reload; the tracked path position is read straight off the tracker each tick in tick(), so it needs no per-field callback.
    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (CONSIST_ID.equals(data)) {
            String synced = this.dataTracker.get(CONSIST_ID);
            if (!synced.isEmpty()) {
                try {
                    this.consistId = java.util.UUID.fromString(synced);
                } catch (IllegalArgumentException ignored) {
                    // Keep the current id if the synced value is corrupt.
                }
            }
        }
    }

    public String getHudLine() {
        return this.dataTracker.get(HUD_LINE);
    }

    public String getHudDirection() {
        return this.dataTracker.get(HUD_DIRECTION);
    }

    public String getHudNextStation() {
        return this.dataTracker.get(HUD_NEXT_STATION);
    }

    public String getHudAnnouncement() {
        return this.dataTracker.get(HUD_ANNOUNCEMENT);
    }

    public boolean isHudWaiting() {
        return this.dataTracker.get(HUD_WAITING);
    }

    public int getHudLineColor() {
        return this.dataTracker.get(HUD_LINE_COLOR);
    }

    public boolean isHudArriving() {
        return this.dataTracker.get(HUD_ARRIVING);
    }

    // Called by the consist for every car so all riders share one readout.
    public void setHudInfo(String line, String direction, String nextStation, boolean waiting, int lineColor, boolean arriving) {
        if (!this.dataTracker.get(HUD_LINE).equals(line)) {
            this.dataTracker.set(HUD_LINE, line);
        }
        if (!this.dataTracker.get(HUD_DIRECTION).equals(direction)) {
            this.dataTracker.set(HUD_DIRECTION, direction);
        }
        if (!this.dataTracker.get(HUD_NEXT_STATION).equals(nextStation)) {
            this.dataTracker.set(HUD_NEXT_STATION, nextStation);
        }
        if (this.dataTracker.get(HUD_WAITING) != waiting) {
            this.dataTracker.set(HUD_WAITING, waiting);
        }
        if (this.dataTracker.get(HUD_LINE_COLOR) != lineColor) {
            this.dataTracker.set(HUD_LINE_COLOR, lineColor);
        }
        if (this.dataTracker.get(HUD_ARRIVING) != arriving) {
            this.dataTracker.set(HUD_ARRIVING, arriving);
        }
    }

    public void setHudAnnouncement(String announcement) {
        if (!this.dataTracker.get(HUD_ANNOUNCEMENT).equals(announcement)) {
            this.dataTracker.set(HUD_ANNOUNCEMENT, announcement);
        }
    }

    public float getArcLength() {
        return this.dataTracker.get(ARC_LENGTH);
    }

    public void setArcLength(float value) {
        this.dataTracker.set(ARC_LENGTH, value);
    }

    public float getPathYaw() {
        return this.dataTracker.get(PATH_YAW);
    }

    public void setPathYaw(float value) {
        this.dataTracker.set(PATH_YAW, value);
    }

    public float getPathPitch() {
        return this.dataTracker.get(PATH_PITCH);
    }

    public void setPathPitch(float value) {
        this.dataTracker.set(PATH_PITCH, value);
    }

    // Linearly interpolated orientation for smooth client rendering; a jump too large to be motion (a terminus turn-around flipping the car about 180 degrees) is snapped rather than spun through.
    public float getLerpedPathYaw(float tickDelta) {
        float target = this.getPathYaw();
        if (Math.abs(MathHelper.subtractAngles(this.prevPathYaw, target)) > 135.0f) {
            return target;
        }
        return MathHelper.lerpAngleDegrees(tickDelta, this.prevPathYaw, target);
    }

    public float getLerpedPathPitch(float tickDelta) {
        return MathHelper.lerp(tickDelta, this.prevPathPitch, this.getPathPitch());
    }

    @Override
    public void tick() {
        super.tick();
        // On the client, advance the interpolator so the car eases between tracked packets; the server drives position from the consist.
        if (this.getEntityWorld().isClient()) {
            snapOnDiscontinuity();
            // Re-aim the interpolator at the latest tracked position every tick so the body advances even when only one coordinate changed or a screen (a world map) stalled the client between packets, rather than freezing on a stale target until the next POS_Z update.
            this.interpolator.refreshPositionAndAngles(trackedPos(), this.getYaw(), this.getPitch());
            this.interpolator.tick();
            resyncIfStranded();
            logClientDiag();
        }
        // Carry orientation forward each tick so the render lerp has a baseline.
        this.prevPathYaw = this.getPathYaw();
        this.prevPathPitch = this.getPathPitch();
    }

    // Rescues a car whose interpolator has wedged: a fast turn can leave the body frozen off the rail while its synced position keeps advancing, so when it falls too far behind to be one tick of lag, clear the interpolator and snap straight onto the tracked position.
    private void resyncIfStranded() {
        Vec3d target = trackedPos();
        if (this.getEntityPos().squaredDistanceTo(target) > POS_RESYNC_SQ) {
            this.interpolator.clear();
            this.refreshPositionAndAngles(target, this.getYaw(), this.getPitch());
        }
    }

    // When the arc jumps too far to be one tick of motion (a terminus reverse or a chunk-reload teleport), clear the interpolator and orientation baseline so the car hard-snaps to its new pose instead of sliding across the gap.
    private void snapOnDiscontinuity() {
        float arc = this.getArcLength();
        if (Math.abs(arc - this.lastClientArc) > ARC_DISCONTINUITY) {
            this.interpolator.clear();
            this.refreshPositionAndAngles(trackedPos(), this.getYaw(), this.getPitch());
            this.prevPathYaw = this.getPathYaw();
            this.prevPathPitch = this.getPathPitch();
        }
        this.lastClientArc = arc;
    }

    @Override
    public PositionInterpolator getInterpolator() {
        return this.interpolator;
    }

    // Logs on the client when a car leaves the world, so a train that renders with too few cars can be traced to the removal reason and tick it happened on.
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (this.getEntityWorld().isClient()) {
            info.mudbourn.mmsmetro.MmsMetro.LOGGER.info(String.format(
                "[diag-remove] cid %s idx %d reason %s age %d pos (%.2f,%.2f,%.2f)",
                shortId(), this.getCarIndex(), reason, this.age,
                this.getX(), this.getY(), this.getZ()));
        }
        super.remove(reason);
    }

    // Exposes the protected client tick counter so the scan can tell a frozen car (age not advancing) from an unloaded chunk or a real removal.
    public int clientAge() {
        return this.age;
    }

    // Wall-clock millis of the last frame the renderer drew this car, stamped client-side so the scan can flag a car that is present but has stopped being drawn.
    public long lastRenderMs;

    // First eight characters of the consist id, enough to group a train's cars in the log.
    private String shortId() {
        String s = this.consistId.toString();
        return s.length() >= 8 ? s.substring(0, 8) : s;
    }

    // Once a second, logs this car's client position and velocity so a rider can report whether the followers stay synced with the lead through the run.
    private void logClientDiag() {
        if (this.age % 20 != 0) {
            return;
        }
        Vec3d p = this.getEntityPos();
        Vec3d v = this.getVelocity();
        info.mudbourn.mmsmetro.MmsMetro.LOGGER.info(String.format(
            "[diag-cli] cid %s idx %d arc %.3f pos (%.2f,%.2f,%.2f) vel (%.3f,%.3f,%.3f)",
            shortId(), this.getCarIndex(), this.getArcLength(),
            p.x, p.y, p.z, v.x, v.y, v.z));
    }

    public java.util.UUID getConsistId() {
        return this.consistId;
    }

    public void setConsistId(java.util.UUID value) {
        this.consistId = value;
        this.dataTracker.set(CONSIST_ID, value.toString());
    }

    public int getCarIndex() {
        return this.dataTracker.get(CAR_INDEX);
    }

    public void setCarIndex(int value) {
        this.dataTracker.set(CAR_INDEX, value);
    }

    public net.minecraft.util.math.BlockPos getPathOrigin() {
        return this.pathOrigin;
    }

    public net.minecraft.util.math.Direction getPathInitialDir() {
        return this.pathInitialDir;
    }

    // Stamped by the consist so the path's identity rides along on the car and survives a reload.
    public void setPathIdentity(net.minecraft.util.math.BlockPos origin, net.minecraft.util.math.Direction initialDir) {
        this.pathOrigin = origin;
        this.pathInitialDir = initialDir;
    }

    @Override
    protected void readCustomData(ReadView view) {
        this.setArcLength(view.getFloat("ArcLength", 0.0f));
        this.setCarIndex(view.getInt("CarIndex", 0));
        this.setPathYaw(view.getFloat("PathYaw", 0.0f));
        this.setPathPitch(view.getFloat("PathPitch", 0.0f));
        this.prevPathYaw = this.getPathYaw();
        this.prevPathPitch = this.getPathPitch();
        String stored = view.getString("ConsistId", "");
        if (!stored.isEmpty()) {
            try {
                this.setConsistId(java.util.UUID.fromString(stored));
            } catch (IllegalArgumentException ignored) {
                // Keep the freshly generated id if the stored value is corrupt.
            }
        }
        if (view.getInt("PathOriginSet", 0) != 0) {
            this.pathOrigin = new net.minecraft.util.math.BlockPos(
                view.getInt("PathOriginX", 0), view.getInt("PathOriginY", 0), view.getInt("PathOriginZ", 0));
        }
        this.pathInitialDir = parseHorizontalDir(view.getString("PathInitialDir", ""));
    }

    // The horizontal direction a saved name spells, or null when it names none.
    private static net.minecraft.util.math.Direction parseHorizontalDir(String name) {
        return switch (name) {
            case "north" -> net.minecraft.util.math.Direction.NORTH;
            case "south" -> net.minecraft.util.math.Direction.SOUTH;
            case "east" -> net.minecraft.util.math.Direction.EAST;
            case "west" -> net.minecraft.util.math.Direction.WEST;
            default -> null;
        };
    }

    @Override
    protected void writeCustomData(WriteView view) {
        view.putFloat("ArcLength", this.getArcLength());
        view.putInt("CarIndex", this.getCarIndex());
        view.putFloat("PathYaw", this.getPathYaw());
        view.putFloat("PathPitch", this.getPathPitch());
        view.putString("ConsistId", this.consistId.toString());
        if (this.pathOrigin != null) {
            view.putInt("PathOriginSet", 1);
            view.putInt("PathOriginX", this.pathOrigin.getX());
            view.putInt("PathOriginY", this.pathOrigin.getY());
            view.putInt("PathOriginZ", this.pathOrigin.getZ());
        }
        if (this.pathInitialDir != null) {
            view.putString("PathInitialDir", this.pathInitialDir.asString());
        }
    }

    // Seat slots inside the car body as {forward, lateral} offsets in blocks, ordered centre-outward so a lone rider sits in the middle, spanning the minecart's length and width so no one sits over the coupling gap.
    private static final double[][] SEAT_SLOTS = {
        {0.0, 0.0},
        {-0.5, -0.25}, {-0.5, 0.25},
        {0.5, -0.25}, {0.5, 0.25},
    };
    private static final int MAX_PASSENGERS = SEAT_SLOTS.length;
    private static final double SEAT_HEIGHT = 0.1;

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (this.getEntityWorld().isClient()) {
            return ActionResult.SUCCESS;
        }
        if (player.shouldCancelInteraction()) {
            return ActionResult.PASS;
        }
        if (this.getPassengerList().size() >= MAX_PASSENGERS) {
            return ActionResult.PASS;
        }
        return player.startRiding(this) ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengerList().size() < MAX_PASSENGERS;
    }

    // Seats each rider at a distinct slot, rotated into world space by the car's path heading, so riders sit in formation and turn with the car.
    @Override
    public Vec3d getPassengerRidingPos(Entity passenger) {
        int index = this.getPassengerList().indexOf(passenger);
        if (index < 0 || index >= SEAT_SLOTS.length) {
            index = 0;
        }
        double forward = SEAT_SLOTS[index][0];
        double lateral = SEAT_SLOTS[index][1];

        double yawRad = Math.toRadians(this.getPathYaw());
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        // Minecraft yaw 0 faces +Z; forward = (-sin, cos), right = (cos, sin).
        double dx = -sin * forward + cos * lateral;
        double dz = cos * forward + sin * lateral;
        return this.getEntityPos().add(dx, SEAT_HEIGHT, dz);
    }

    @Override
    public boolean shouldRender(double distance) {
        return true;
    }

    // Vanilla Entity is not pickable by default; without this the attack raycast skips the car, so left-clicking it with the spawner never registers a hit.
    @Override
    public boolean canHit() {
        return !this.isRemoved();
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }
}
