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

    // Every settable key, for command autocomplete and validation.
    public static final java.util.List<String> KEYS = java.util.List.of(
        "maxSpeed", "acceleration", "carSpacing", "carsPerTrain",
        "dwellTicks", "headway", "chunkRadius");

    // Applies a value to a named field, returning false for an unknown key or an
    // unparseable value. Saves on success so the change survives a restart.
    public boolean set(String key, String value) {
        try {
            switch (key) {
                case "maxSpeed" -> this.maxSpeed = Double.parseDouble(value);
                case "acceleration" -> this.acceleration = Double.parseDouble(value);
                case "carSpacing" -> this.carSpacing = Double.parseDouble(value);
                case "carsPerTrain" -> this.carsPerTrain = Integer.parseInt(value);
                case "dwellTicks" -> this.dwellTicks = Integer.parseInt(value);
                case "headway" -> this.headway = Double.parseDouble(value);
                case "chunkRadius" -> this.chunkRadius = Integer.parseInt(value);
                default -> {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        save();
        return true;
    }

    // A single-line dump of every field, for the config command with no value.
    public String describe() {
        return "maxSpeed=" + maxSpeed
            + ", acceleration=" + acceleration
            + ", carSpacing=" + carSpacing
            + ", carsPerTrain=" + carsPerTrain
            + ", dwellTicks=" + dwellTicks
            + ", headway=" + headway
            + ", chunkRadius=" + chunkRadius;
    }

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
