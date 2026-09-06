package info.mudbourn.mmsmetro.block.entity;

import info.mudbourn.mmsmetro.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;

// A station speaker's stored identity. For now it just remembers an
// announcement label; the authoring/playback system is not built yet.
public class SpeakerBlockEntity extends BlockEntity {

    private String announcement = "";

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEAKER, pos, state);
    }

    public String getAnnouncement() {
        return this.announcement;
    }

    public void setAnnouncement(String value) {
        this.announcement = value;
        this.markDirty();
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        this.announcement = view.getString("Announcement", "");
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);
        view.putString("Announcement", this.announcement);
    }
}
