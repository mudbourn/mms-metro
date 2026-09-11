package info.mudbourn.mmsmetro.block;

import com.mojang.serialization.MapCodec;
import info.mudbourn.mmsmetro.block.entity.JunctionBlockEntity;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

// Placed under a rail node, this routes the path walker by the direction a train arrives on, so a Spanish-solution terminus can send arrivals and departures down separate tracks instead of the walker greedily doubling back.
public class JunctionBlock extends BlockWithEntity {

    public JunctionBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return createCodec(JunctionBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new JunctionBlockEntity(pos, state);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer
            && world.getBlockEntity(pos) instanceof JunctionBlockEntity junction) {
            MetroNetworking.openJunction(serverPlayer, pos, junction);
        }
        return ActionResult.SUCCESS;
    }
}
