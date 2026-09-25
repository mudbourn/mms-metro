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
                // Minecart-height box keeps a seated rider's eye above their own car, so the crosshair can reach a sibling car in either direction.
                .dimensions(1.4f, 0.9f)
                // A distant train stays tracked and visible; the send interval is left at the default so followers keep dead-reckoning off their velocity between packets and stay as fresh as the ridden car.
                .maxTrackingRange(10)
                .build(key)
        );
    }
}
