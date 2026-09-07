package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

// A single creative tab that holds every mms-metro item and block.
public final class ModItemGroups {

    public static ItemGroup METRO;

    public static void register() {
        Identifier id = Identifier.of(MmsMetro.MOD_ID, "metro");
        RegistryKey<ItemGroup> key = RegistryKey.of(Registries.ITEM_GROUP.getKey(), id);

        METRO = Registry.register(Registries.ITEM_GROUP, key, ItemGroup.create(ItemGroup.Row.TOP, 0)
            .displayName(Text.translatable("itemGroup.mms_metro.metro"))
            .icon(() -> new ItemStack(ModItems.METRO_SPAWNER))
            .entries((context, entries) -> {
                entries.add(ModItems.METRO_SPAWNER);
                entries.add(ModBlocks.STATION);
                entries.add(ModBlocks.SPEAKER);
                entries.add(ModBlocks.SPEED_BUMP);
            })
            .build());
    }
}
