package info.mudbourn.mmsmetro.mixin;

import info.mudbourn.mmsmetro.train.ConsistManager;
import info.mudbourn.mmsmetro.train.MetroState;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Tells the saved train state when a block that shapes a baked path changes.
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    // Dismounts non-player riders from the views before the world saves, since views are never written.
    @Inject(method = "save", at = @At("HEAD"))
    private void mmsMetro$dismountMobs(ProgressListener progress, boolean flush, boolean savingDisabled, CallbackInfo ci) {
        ConsistManager.dismountMobs((ServerWorld) (Object) this);
    }

    // Marks the paths through the changed block for rebake.
    @Inject(method = "onBlockStateChanged", at = @At("HEAD"))
    private void mmsMetro$markPathsDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        MetroState.onBlockChanged((ServerWorld) (Object) this, pos, oldState, newState);
    }
}
