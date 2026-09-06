package info.mudbourn.mmsmetro.registry;

import info.mudbourn.mmsmetro.MmsMetro;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

// Registers the mod's custom train sound events. Rolling and rail noise reuse
// vanilla sounds; these four cover the station stop cycle.
public final class ModSounds {

    // Plays as a train brakes into a station.
    public static SoundEvent TRAIN_INCOMING;

    // Plays the moment a train stops at a station.
    public static SoundEvent ARRIVAL;

    // Loops while a train dwells at a station.
    public static SoundEvent BUFFER_WAIT;

    // Plays as a train pulls away from a station.
    public static SoundEvent DEPARTURE;

    public static void register() {
        TRAIN_INCOMING = register("train_incoming");
        ARRIVAL = register("arrival");
        BUFFER_WAIT = register("buffer_wait");
        DEPARTURE = register("departure");
    }

    private static SoundEvent register(String name) {
        Identifier id = Identifier.of(MmsMetro.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
}
