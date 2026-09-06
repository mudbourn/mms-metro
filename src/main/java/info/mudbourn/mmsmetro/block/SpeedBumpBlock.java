package info.mudbourn.mmsmetro.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

// A trackside announcement trigger. A train passing over it looks ahead to the
// next station and announces the arrival ("Arriving at: X, exit on the Y."). It
// stores no station data of its own — the station is the single source of truth.
// Right-clicking toggles terminus mode, which makes the announcement say the
// upcoming stop is a terminal station.
public class SpeedBumpBlock extends Block {

    public static final MapCodec<SpeedBumpBlock> CODEC = createCodec(SpeedBumpBlock::new);

    public static final BooleanProperty TERMINUS = Properties.ENABLED;

    public SpeedBumpBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getDefaultState().with(TERMINUS, false));
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(TERMINUS);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient()) {
            boolean terminus = !state.get(TERMINUS);
            world.setBlockState(pos, state.with(TERMINUS, terminus));
            player.sendMessage(Text.literal(terminus
                ? "Speed bump set to terminus: announces the next stop as a terminal station."
                : "Speed bump set to through: announces the next stop normally."), true);
        }
        return ActionResult.SUCCESS;
    }
}
