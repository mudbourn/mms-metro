package info.mudbourn.mmsmetro.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import info.mudbourn.mmsmetro.MmsMetro;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MetroConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String FILE_NAME = "mms_metro.json";

    // Top cruising speed in blocks per tick.
    public double maxSpeed = 0.4;

    // Change in speed per tick while accelerating or braking.
    public double acceleration = 0.02;

    // Gap between car centers in blocks, measured along the track.
    public double carSpacing = 3.0;

    // Number of cars a spawner creates, including the lead.
    public int carsPerTrain = 3;

    // Ticks a train holds at a station before departing.
    public int dwellTicks = 100;

    // Minimum along-track headway a train keeps behind the train ahead.
    public double headway = 12.0;

    // Radius in chunks the consist force-loads around itself.
    public int chunkRadius = 2;

    public static MetroConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            MetroConfig fresh = new MetroConfig();
            fresh.save();
            return fresh;
        }

        try {
            MetroConfig loaded = GSON.fromJson(Files.readString(path), MetroConfig.class);
            return loaded != null ? loaded : new MetroConfig();
        } catch (IOException e) {
            MmsMetro.LOGGER.error("Failed to read {}, using defaults", FILE_NAME, e);
            return new MetroConfig();
        }
    }

    public void save() {
        try {
            Files.writeString(configPath(), GSON.toJson(this));
        } catch (IOException e) {
            MmsMetro.LOGGER.error("Failed to write {}", FILE_NAME, e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
