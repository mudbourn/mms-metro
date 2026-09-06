package info.mudbourn.mmsmetro.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.MathHelper;
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

    @Override
    public boolean shouldRender(double distance) {
        return true;
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }
}
