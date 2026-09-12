package info.mudbourn.mmsmetro.block.entity;

import info.mudbourn.mmsmetro.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

// Holds a bump's editable direction, matched against the track's heading of travel so a bump only heralds stops on its own side of the track.
public class SpeedBumpBlockEntity extends BlockEntity {

    private String direction = "";

    public SpeedBumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEED_BUMP, pos, state);
    }

    public String getDirection() {
        return this.direction;
    }

    public void setDirection(String value) {
        this.direction = value;
        this.markDirty();
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.direction = view.getString("Direction", "");
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putString("Direction", this.direction);
    }
}
