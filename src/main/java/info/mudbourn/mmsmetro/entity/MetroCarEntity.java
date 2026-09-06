package info.mudbourn.mmsmetro.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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

    // Path-derived orientation, tracked explicitly so the client rotates the
    // model. Vanilla rotation sync is unreliable for a teleport-driven entity.
    private static final TrackedData<Float> PATH_YAW =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> PATH_PITCH =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Onboard-HUD fields, stamped onto every car by the consist each tick so any
    // rider — not just the lead car's — reads the same live line, direction, and
    // next stop. Announcement carries the transient "Arriving at..." cue.
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

    // Client-side interpolation of the tracked orientation.
    private float prevPathYaw;
    private float prevPathPitch;

    // Groups the cars of one train so a single car can be traced back to its
    // whole consist even after a reload, when the in-memory registry is gone.
    private java.util.UUID consistId = java.util.UUID.randomUUID();

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

    // Called by the consist for every car so all riders share one readout.
    public void setHudInfo(String line, String direction, String nextStation, boolean waiting) {
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

    // Linearly interpolated orientation for smooth client rendering.
    public float getLerpedPathYaw(float tickDelta) {
        return MathHelper.lerpAngleDegrees(tickDelta, this.prevPathYaw, this.getPathYaw());
    }

    public float getLerpedPathPitch(float tickDelta) {
        return MathHelper.lerp(tickDelta, this.prevPathPitch, this.getPathPitch());
    }

    @Override
    public void tick() {
        super.tick();
        // Carry orientation forward each tick so the render lerp has a baseline.
        this.prevPathYaw = this.getPathYaw();
        this.prevPathPitch = this.getPathPitch();
    }

    public java.util.UUID getConsistId() {
        return this.consistId;
    }

    public void setConsistId(java.util.UUID value) {
        this.consistId = value;
    }

    public int getCarIndex() {
        return this.dataTracker.get(CAR_INDEX);
    }

    public void setCarIndex(int value) {
        this.dataTracker.set(CAR_INDEX, value);
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
                this.consistId = java.util.UUID.fromString(stored);
            } catch (IllegalArgumentException ignored) {
                // Keep the freshly generated id if the stored value is corrupt.
            }
        }
    }

    @Override
    protected void writeCustomData(WriteView view) {
        view.putFloat("ArcLength", this.getArcLength());
        view.putInt("CarIndex", this.getCarIndex());
        view.putFloat("PathYaw", this.getPathYaw());
        view.putFloat("PathPitch", this.getPathPitch());
        view.putString("ConsistId", this.consistId.toString());
    }

    // Seat layout: riders fill a grid of slots inside the car body, laid out
    // along the car's length (rows) and across its width (columns).
    private static final int SEAT_ROWS = 3;
    private static final int SEAT_COLS = 2;
    private static final int MAX_PASSENGERS = SEAT_ROWS * SEAT_COLS;
    private static final double SEAT_ROW_SPACING = 0.7;
    private static final double SEAT_COL_SPACING = 0.6;
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

    // Seats each rider at a distinct slot, rotated into world space by the car's
    // path heading, so riders sit in formation and turn with the car.
    @Override
    public Vec3d getPassengerRidingPos(Entity passenger) {
        int index = this.getPassengerList().indexOf(passenger);
        if (index < 0) {
            index = 0;
        }
        int row = index / SEAT_COLS;
        int col = index % SEAT_COLS;

        // Local offsets: forward along the car (centered), lateral across it.
        double forward = (row - (SEAT_ROWS - 1) / 2.0) * SEAT_ROW_SPACING;
        double lateral = (col - (SEAT_COLS - 1) / 2.0) * SEAT_COL_SPACING;

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

    // Vanilla Entity is not pickable by default; without this the attack raycast
    // skips the car, so left-clicking it with the spawner never registers a hit.
    @Override
    public boolean canHit() {
        return !this.isRemoved();
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }
}
