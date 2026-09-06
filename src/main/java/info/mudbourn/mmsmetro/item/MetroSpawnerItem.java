package info.mudbourn.mmsmetro.item;

import info.mudbourn.mmsmetro.MmsMetro;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;

// Right-clicking a rail with this spawns a train there.
public class MetroSpawnerItem extends Item {

    public MetroSpawnerItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (!context.getWorld().isClient()) {
            MmsMetro.LOGGER.info("Metro spawner used at {}", context.getBlockPos());
        }

        return ActionResult.SUCCESS;
    }
}
