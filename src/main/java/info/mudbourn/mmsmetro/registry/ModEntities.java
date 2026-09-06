package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

// Registers the mod's entity types.
public final class ModEntities {

    public static EntityType<MetroCarEntity> METRO_CAR;

    public static void register() {
        Identifier id = Identifier.of(MmsMetro.MOD_ID, "metro_car");
        RegistryKey<EntityType<?>> key = RegistryKey.of(Registries.ENTITY_TYPE.getKey(), id);

        METRO_CAR = Registry.register(
            Registries.ENTITY_TYPE,
            id,
            EntityType.Builder.create(MetroCarEntity::new, SpawnGroup.MISC)
                .dimensions(1.4f, 1.4f)
                .build(key)
        );
    }
}
