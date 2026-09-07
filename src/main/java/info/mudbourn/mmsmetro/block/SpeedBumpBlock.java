package info.mudbourn.mmsmetro.block;

import com.mojang.serialization.MapCodec;
import info.mudbourn.mmsmetro.block.entity.SpeedBumpBlockEntity;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

// A trackside trigger that announces the upcoming stop and arms the ease-down braking; it stores an editable direction so it only heralds stops on its own side of the track, plus a terminus toggle on the block state.
public class SpeedBumpBlock extends BlockWithEntity {

    public static final BooleanProperty TERMINUS = Properties.ENABLED;

    public SpeedBumpBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getDefaultState().with(TERMINUS, false));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return createCodec(SpeedBumpBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SpeedBumpBlockEntity(pos, state);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(TERMINUS);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer
            && world.getBlockEntity(pos) instanceof SpeedBumpBlockEntity bump) {
            MetroNetworking.openBump(serverPlayer, pos, state.get(TERMINUS), bump.getDirection());
        }
        return ActionResult.SUCCESS;
    }
}
