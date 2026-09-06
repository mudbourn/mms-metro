package info.mudbourn.mmsmetro;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MmsMetro implements ModInitializer {

    public static final String MOD_ID = "mms_metro";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("mms-metro initializing");
    }
}
