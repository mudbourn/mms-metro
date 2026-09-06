package info.mudbourn.mmsmetro.item;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.train.ConsistManager;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// Right-clicking a rail with this spawns a train there.
public class MetroSpawnerItem extends Item {

    public MetroSpawnerItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (!(context.getWorld() instanceof ServerWorld world)) {
            return ActionResult.SUCCESS;
        }

        BlockPos pos = context.getBlockPos();
        if (!AbstractRailBlock.isRail(world, pos)) {
            return ActionResult.PASS;
        }

        PlayerEntity player = context.getPlayer();
        Direction facing = player != null ? player.getHorizontalFacing() : Direction.NORTH;
        ConsistManager.spawn(world, pos, facing, MmsMetro.config().carsPerTrain, MmsMetro.config());
        return ActionResult.SUCCESS;
    }
}
