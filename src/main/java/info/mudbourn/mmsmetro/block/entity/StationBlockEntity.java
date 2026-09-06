package info.mudbourn.mmsmetro.block.entity;

import info.mudbourn.mmsmetro.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

// Holds a station's editable identity: name, line, and dwell time.
public class StationBlockEntity extends BlockEntity {

    private String stationName = "";

    private String lineName = "";

    private int dwellTicks = 100;

    public StationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATION, pos, state);
    }

    public String getStationName() {
        return this.stationName;
    }

    public void setStationName(String value) {
        this.stationName = value;
        this.markDirty();
    }

    public String getLineName() {
        return this.lineName;
    }

    public void setLineName(String value) {
        this.lineName = value;
        this.markDirty();
    }

    public int getDwellTicks() {
        return this.dwellTicks;
    }

    public void setDwellTicks(int value) {
        this.dwellTicks = value;
        this.markDirty();
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.stationName = view.getString("StationName", "");
        this.lineName = view.getString("LineName", "");
        this.dwellTicks = view.getInt("DwellTicks", 100);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putString("StationName", this.stationName);
        view.putString("LineName", this.lineName);
        view.putInt("DwellTicks", this.dwellTicks);
    }
}
