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
import net.minecraft.world.World;

// A train car: a pure display entity driven by the consist, not vanilla physics.
public class MetroCarEntity extends Entity {

    // Position along the resolved path in blocks, from the lead's origin.
    private static final TrackedData<Float> ARC_LENGTH =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Zero for the lead car, increasing toward the tail.
    private static final TrackedData<Integer> CAR_INDEX =
        DataTracker.registerData(MetroCarEntity.class, TrackedDataHandlerRegistry.INTEGER);

    public MetroCarEntity(EntityType<? extends MetroCarEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(ARC_LENGTH, 0.0f);
        builder.add(CAR_INDEX, 0);
    }

    public float getArcLength() {
        return this.dataTracker.get(ARC_LENGTH);
    }

    public void setArcLength(float value) {
        this.dataTracker.set(ARC_LENGTH, value);
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
    }

    @Override
    protected void writeCustomData(WriteView view) {
        view.putFloat("ArcLength", this.getArcLength());
        view.putInt("CarIndex", this.getCarIndex());
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
