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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

// A train car: a pure display entity driven by the consist, not vanilla physics.
public class MetroCarEntity extends Entity {

    // Position along the path in blocks.
    private static final TrackedData<Float> ARC_LENGTH =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Zero for the lead car, increasing toward the tail.
    private static final TrackedData<Integer> CAR_INDEX =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Path-derived orientation synced to the client.
    private static final TrackedData<Float> PATH_YAW =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> PATH_PITCH =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Onboard HUD line name.
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

    // ARGB colour the HUD tints the line name with.
    private static final TrackedData<Integer> HUD_LINE_COLOR =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // True while the train is braking into a station.
    private static final TrackedData<Boolean> HUD_ARRIVING =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // Cars in the whole train, synced so the client can tell when some are missing.
    private static final TrackedData<Integer> CAR_COUNT =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // The train id as a string, synced so the client can group a train's cars.
    private static final TrackedData<String> CONSIST_ID =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.STRING);

    // Path position carried through the DataTracker, which the client drives the body from.
    private static final TrackedData<Float> POS_X =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> POS_Y =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> POS_Z =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Previous tracked orientation for client-side interpolation.
    private float prevPathYaw;
    private float prevPathPitch;

    // The arc seen on the client last tick.
    private float lastClientArc;

    // An arc step larger than this between ticks snaps the car to its new pose.
    private static final float ARC_DISCONTINUITY = 8.0f;

    // Smooths the car's position between per-tick teleports.
    private final PositionInterpolator interpolator = new PositionInterpolator(this, 1);

    // The id of the train this car is a view of.
    private UUID consistId = UUID.randomUUID();

    // The rail and heading an older version saved, read only to migrate the car into a saved train.
    private BlockPos pathOrigin;

    private Direction pathInitialDir;

    private boolean spawnedView;

    public MetroCarEntity(EntityType<? extends MetroCarEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(ARC_LENGTH, 0.0f);
        builder.add(CAR_INDEX, 0);
        builder.add(CAR_COUNT, 0);
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

    // Publishes this tick's path position onto the DataTracker.
    public void setTrackedPos(double x, double y, double z) {
        this.dataTracker.set(POS_X, (float) x);
        this.dataTracker.set(POS_Y, (float) y);
        this.dataTracker.set(POS_Z, (float) z);
    }

    private Vec3d trackedPos() {
        return new Vec3d(this.dataTracker.get(POS_X), this.dataTracker.get(POS_Y), this.dataTracker.get(POS_Z));
    }

    // Mirrors the synced train id onto the client.
    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (CONSIST_ID.equals(data)) {
            String synced = this.dataTracker.get(CONSIST_ID);
            if (!synced.isEmpty()) {
                try {
                    this.consistId = UUID.fromString(synced);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (this.age == 0) {
            this.prevPathYaw = this.getPathYaw();
            this.prevPathPitch = this.getPathPitch();
            this.lastClientArc = this.getArcLength();
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

    // Sets the shared onboard readout.
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

    // Interpolated orientation for rendering, snapping across a jump too large to be motion.
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
        if (this.getEntityWorld().isClient()) {
            snapOnDiscontinuity();
            this.interpolator.refreshPositionAndAngles(trackedPos(), this.getYaw(), this.getPitch());
            this.interpolator.tick();
        }
        this.prevPathYaw = this.getPathYaw();
        this.prevPathPitch = this.getPathPitch();
    }

    // Clears the interpolator and orientation baseline when the arc jumps.
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

    public UUID getConsistId() {
        return this.consistId;
    }

    public void setConsistId(UUID value) {
        this.consistId = value;
        this.dataTracker.set(CONSIST_ID, value.toString());
    }

    public int getCarIndex() {
        return this.dataTracker.get(CAR_INDEX);
    }

    public void setCarIndex(int value) {
        this.dataTracker.set(CAR_INDEX, value);
    }

    public int getCarCount() {
        return this.dataTracker.get(CAR_COUNT);
    }

    public void setCarCount(int value) {
        if (this.dataTracker.get(CAR_COUNT) != value) {
            this.dataTracker.set(CAR_COUNT, value);
        }
    }

    public BlockPos getPathOrigin() {
        return this.pathOrigin;
    }

    public Direction getPathInitialDir() {
        return this.pathInitialDir;
    }

    // True for the entities the saved train spawns, false for cars an older version saved.
    public boolean isView() {
        return this.spawnedView;
    }

    public void markView() {
        this.spawnedView = true;
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
                this.setConsistId(UUID.fromString(stored));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (view.getInt("PathOriginSet", 0) != 0) {
            this.pathOrigin = new BlockPos(
                view.getInt("PathOriginX", 0), view.getInt("PathOriginY", 0), view.getInt("PathOriginZ", 0));
        }
        this.pathInitialDir = parseHorizontalDir(view.getString("PathInitialDir", ""));
    }

    // The horizontal direction a saved name spells, or null.
    private static Direction parseHorizontalDir(String name) {
        return switch (name) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    @Override
    protected void writeCustomData(WriteView view) {
    }

    // Never saved with the chunk or a rider.
    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean saveData(WriteView view) {
        return false;
    }

    // Seat slots as {forward, lateral} offsets in blocks, centre outward.
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
        if (this.hasPassengers()) {
            return ActionResult.PASS;
        }
        boolean hop = player.getVehicle() instanceof MetroCarEntity && player.getVehicle() != this;
        return player.startRiding(this, hop, false) ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengerList().size() < MAX_PASSENGERS;
    }

    // Seats each rider at a distinct slot rotated by the car's path heading.
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
        double dx = -sin * forward + cos * lateral;
        double dz = cos * forward + sin * lateral;
        return this.getEntityPos().add(dx, SEAT_HEIGHT, dz);
    }

    @Override
    public boolean shouldRender(double distance) {
        return true;
    }

    // Lets the attack raycast hit the car.
    @Override
    public boolean canHit() {
        return !this.isRemoved();
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }
}
