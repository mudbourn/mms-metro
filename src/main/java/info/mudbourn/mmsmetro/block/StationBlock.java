package info.mudbourn.mmsmetro.block;

import com.mojang.serialization.MapCodec;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
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

    // Right-clicking opens the station editor for operators, where every field is edited at once.
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                 PlayerEntity player, BlockHitResult hit) {
        // Only operators edit the metro markers; everyone else interacts with it as a plain block.
        if (!MetroNetworking.canOperate(player)) {
            return ActionResult.PASS;
        }
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer
            && world.getBlockEntity(pos) instanceof StationBlockEntity station) {
            MetroNetworking.openStation(serverPlayer, pos, station);
        }
        return ActionResult.SUCCESS;
    }
}
