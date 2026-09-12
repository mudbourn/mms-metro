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

    // The line's colour, stored as a DyeColor name (e.g. "red"); tints the line on the onboard HUD.
    private String lineColor = "white";

    // Fixed direction label for this stop (e.g. "Northbound"), shown verbatim on the HUD only when fixedDirection is set; otherwise the train shows its compass heading of travel.
    private String lineDirection = "";

    // When true, the train shows this stop's typed lineDirection instead of its derived compass heading; for two-stop loops where the heading alone would not name the line.
    private boolean fixedDirection = false;

    // Name of the next station down the line, for the HUD "Next stop" readout.
    private String nextStation = "";

    // Which way riders leave the train at this stop, e.g. "left" or "right".
    private String exitDirection = "";

    // A transfer hub: the announcement adds a "Transfer for <line>" footnote.
    private boolean hub = false;

    private String transferLine = "";

    private int dwellTicks = 100;

    // When true, a train that stops here turns around instead of continuing; the station's command that steers the train's direction.
    private boolean terminus = false;

    public StationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATION, pos, state);
    }

    public String getLineDirection() {
        return this.lineDirection;
    }

    public void setLineDirection(String value) {
        this.lineDirection = value;
        this.markDirty();
    }

    public boolean isFixedDirection() {
        return this.fixedDirection;
    }

    public void setFixedDirection(boolean value) {
        this.fixedDirection = value;
        this.markDirty();
    }

    public String getNextStation() {
        return this.nextStation;
    }

    public void setNextStation(String value) {
        this.nextStation = value;
        this.markDirty();
    }

    public String getExitDirection() {
        return this.exitDirection;
    }

    public void setExitDirection(String value) {
        this.exitDirection = value;
        this.markDirty();
    }

    public boolean isHub() {
        return this.hub;
    }

    public void setHub(boolean value) {
        this.hub = value;
        this.markDirty();
    }

    public String getTransferLine() {
        return this.transferLine;
    }

    public void setTransferLine(String value) {
        this.transferLine = value;
        this.markDirty();
    }

    public boolean isTerminus() {
        return this.terminus;
    }

    public void setTerminus(boolean value) {
        this.terminus = value;
        this.markDirty();
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

    public String getLineColor() {
        return this.lineColor;
    }

    public void setLineColor(String value) {
        this.lineColor = value;
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
        this.lineColor = view.getString("LineColor", "white");
        this.lineDirection = view.getString("LineDirection", "");
        this.fixedDirection = view.getBoolean("FixedDirection", false);
        this.nextStation = view.getString("NextStation", "");
        this.exitDirection = view.getString("ExitDirection", "");
        this.hub = view.getBoolean("Hub", false);
        this.transferLine = view.getString("TransferLine", "");
        this.dwellTicks = view.getInt("DwellTicks", 100);
        this.terminus = view.getBoolean("Terminus", false);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putString("StationName", this.stationName);
        view.putString("LineName", this.lineName);
        view.putString("LineColor", this.lineColor);
        view.putString("LineDirection", this.lineDirection);
        view.putBoolean("FixedDirection", this.fixedDirection);
        view.putString("NextStation", this.nextStation);
        view.putString("ExitDirection", this.exitDirection);
        view.putBoolean("Hub", this.hub);
        view.putString("TransferLine", this.transferLine);
        view.putInt("DwellTicks", this.dwellTicks);
        view.putBoolean("Terminus", this.terminus);
    }
}
