package info.mudbourn.mmsmetro.block;

import com.mojang.serialization.MapCodec;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
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
        if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
            MetroNetworking.openBump(serverPlayer, pos, state.get(TERMINUS));
        }
        return ActionResult.SUCCESS;
    }
}
