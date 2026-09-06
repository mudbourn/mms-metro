package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.item.MetroSpawnerItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

// Registers the mod's standalone items.
public final class ModItems {

    public static Item METRO_SPAWNER;

    public static void register() {
        METRO_SPAWNER = register("metro_spawner", MetroSpawnerItem::new, new Item.Settings());
    }

    private static Item register(String name, java.util.function.Function<Item.Settings, Item> factory,
                                 Item.Settings settings) {
        Identifier id = Identifier.of(MmsMetro.MOD_ID, name);
        RegistryKey<Item> key = RegistryKey.of(Registries.ITEM.getKey(), id);
        Item item = factory.apply(settings.registryKey(key));

        return Registry.register(Registries.ITEM, id, item);
    }
}
