package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.JunctionBlock;
import info.mudbourn.mmsmetro.block.SpeakerBlock;
import info.mudbourn.mmsmetro.block.SpeedBumpBlock;
import info.mudbourn.mmsmetro.block.StationBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

import java.util.function.Function;

// Registers the mod's blocks and their matching block items.
public final class ModBlocks {

    public static Block STATION;

    public static Block SPEAKER;

    public static Block SPEED_BUMP;

    public static Block JUNCTION;

    public static void register() {
        STATION = register("station_block", StationBlock::new,
            AbstractBlock.Settings.copy(Blocks.IRON_BLOCK));
        SPEAKER = register("speaker_block", SpeakerBlock::new,
            AbstractBlock.Settings.copy(Blocks.NOTE_BLOCK));
        SPEED_BUMP = register("speed_bump", SpeedBumpBlock::new,
            AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE));
        JUNCTION = register("junction_block", JunctionBlock::new,
            AbstractBlock.Settings.copy(Blocks.SMOOTH_STONE));
    }

    private static Block register(String name, Function<AbstractBlock.Settings, Block> factory,
                                  AbstractBlock.Settings settings) {
        Identifier id = Identifier.of(MmsMetro.MOD_ID, name);

        RegistryKey<Block> blockKey = RegistryKey.of(Registries.BLOCK.getKey(), id);
        Block block = factory.apply(settings.registryKey(blockKey));
        Registry.register(Registries.BLOCK, id, block);

        RegistryKey<Item> itemKey = RegistryKey.of(Registries.ITEM.getKey(), id);
        Item.Settings itemSettings = new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey();
        Registry.register(Registries.ITEM, id, new BlockItem(block, itemSettings));

        return block;
    }
}
