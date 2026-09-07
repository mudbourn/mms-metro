package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.block.entity.SpeakerBlockEntity;
import info.mudbourn.mmsmetro.block.entity.SpeedBumpBlockEntity;
import info.mudbourn.mmsmetro.block.entity.StationBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

// Registers block entity types. Runs after ModBlocks so the target block exists.
public final class ModBlockEntities {

    public static BlockEntityType<StationBlockEntity> STATION;

    public static BlockEntityType<SpeakerBlockEntity> SPEAKER;

    public static BlockEntityType<SpeedBumpBlockEntity> SPEED_BUMP;

    public static void register() {
        STATION = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(MmsMetro.MOD_ID, "station_block"),
            FabricBlockEntityTypeBuilder.create(StationBlockEntity::new, ModBlocks.STATION).build()
        );
        SPEAKER = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(MmsMetro.MOD_ID, "speaker_block"),
            FabricBlockEntityTypeBuilder.create(SpeakerBlockEntity::new, ModBlocks.SPEAKER).build()
        );
        SPEED_BUMP = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(MmsMetro.MOD_ID, "speed_bump_block"),
            FabricBlockEntityTypeBuilder.create(SpeedBumpBlockEntity::new, ModBlocks.SPEED_BUMP).build()
        );
    }
}
