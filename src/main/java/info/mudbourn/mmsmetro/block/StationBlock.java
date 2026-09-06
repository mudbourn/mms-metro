package info.mudbourn.mmsmetro.block;

import com.mojang.serialization.MapCodec;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

// A placed station marker that trains register against and dwell at.
public class StationBlock extends BlockWithEntity {

    public StationBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return createCodec(StationBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StationBlockEntity(pos, state);
    }
}
