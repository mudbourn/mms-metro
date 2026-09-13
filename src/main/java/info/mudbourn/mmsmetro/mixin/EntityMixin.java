package info.mudbourn.mmsmetro.mixin;

import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vetoes the prone pose for a player seated in a metro car so a crawl mod cannot lay a rider flat inside the body.
@Mixin(Entity.class)
public abstract class EntityMixin {

    // Redirects a swimming/crawl pose to standing when the entity is a player riding a metro car, before the pose is stored or synced.
    @Inject(method = "setPose", at = @At("HEAD"), cancellable = true)
    private void mmsMetro$blockCrawlWhileRiding(EntityPose pose, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof PlayerEntity)) {
            return;
        }
        if (pose != EntityPose.SWIMMING) {
            return;
        }
        if (!(self.getVehicle() instanceof MetroCarEntity)) {
            return;
        }
        self.setPose(EntityPose.STANDING);
        ci.cancel();
    }
}
