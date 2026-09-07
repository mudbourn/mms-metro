package info.mudbourn.mmsmetro;

import info.mudbourn.mmsmetro.command.MetroCommand;
import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.registry.ModBlockEntities;
import info.mudbourn.mmsmetro.registry.ModBlocks;
import info.mudbourn.mmsmetro.registry.ModEntities;
import info.mudbourn.mmsmetro.registry.ModItemGroups;
import info.mudbourn.mmsmetro.registry.ModItems;
import info.mudbourn.mmsmetro.registry.ModSounds;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import info.mudbourn.mmsmetro.train.ConsistManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MmsMetro implements ModInitializer {

    public static final String MOD_ID = "mms_metro";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MetroConfig config;

    @Override
    public void onInitialize() {
        config = MetroConfig.load();

        ModBlocks.register();
        ModItems.register();
        ModItemGroups.register();
        ModBlockEntities.register();
        ModEntities.register();
        ModSounds.register();

        ConsistManager.init();
        MetroNetworking.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            MetroCommand.register(dispatcher));

        LOGGER.info("mms-metro initialized");
    }

    public static MetroConfig config() {
        return config;
    }
}
