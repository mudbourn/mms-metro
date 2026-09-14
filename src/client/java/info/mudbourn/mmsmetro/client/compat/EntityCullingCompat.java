package info.mudbourn.mmsmetro.client.compat;

import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

// Opts metro cars out of tr7zw's EntityCulling so a teleport-driven car is never occlusion-culled mid-train, without the player editing that mod's config; reflective so the mod stays an optional soft dependency.
public final class EntityCullingCompat {

    // The mod id EntityCulling registers under, absent when the mod is not installed.
    private static final String MOD_ID = "entityculling";

    // The class holding the live mod instance and the dynamic-whitelist hook.
    private static final String MOD_BASE_CLASS = "dev.tr7zw.entityculling.EntityCullingModBase";

    // True once the metro car predicate is registered, so the tick retry stops.
    private static boolean registered;

    // True once registration has failed, so a broken hook logs once and never retries.
    private static boolean failed;

    private EntityCullingCompat() {
    }

    // Registers the always-render predicate once the mod's instance exists, retried each client tick until it takes; a no-op when the mod is absent or already wired.
    public static void tryRegister() {
        if (registered || failed || !FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return;
        }
        try {
            Class<?> modBase = Class.forName(MOD_BASE_CLASS);
            Field instanceField = modBase.getField("instance");
            Object instance = instanceField.get(null);
            if (instance == null) {
                return;
            }
            Method addWhitelist = modBase.getMethod("addDynamicEntityWhitelist", Function.class);
            Function<Entity, Boolean> predicate = entity -> entity instanceof MetroCarEntity;
            addWhitelist.invoke(instance, predicate);
            registered = true;
            MmsMetro.LOGGER.info("mms-metro registered metro cars as EntityCulling-exempt");
        } catch (ReflectiveOperationException e) {
            failed = true;
            MmsMetro.LOGGER.warn("mms-metro could not hook EntityCulling; cars may cull behind walls", e);
        }
    }
}
