package info.mudbourn.mmsmetro.client;

import info.mudbourn.mmsmetro.client.render.MetroCarEntityRenderer;
import info.mudbourn.mmsmetro.client.screen.BumpEditScreen;
import info.mudbourn.mmsmetro.client.screen.JunctionEditScreen;
import info.mudbourn.mmsmetro.client.screen.StationEditScreen;
import info.mudbourn.mmsmetro.MmsMetro;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import info.mudbourn.mmsmetro.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;

public class MmsMetroClient implements ClientModInitializer {

    // Counts client ticks so the presence scan fires about once a second.
    private static int scanTick;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.METRO_CAR, MetroCarEntityRenderer::new);
        HudRenderCallback.EVENT.register((context, tickCounter) ->
            MetroHud.render(context, MinecraftClient.getInstance()));

        // Scans every loaded car from outside their own tick, so a car that has stopped ticking still reports its frozen age and whether its chunk is loaded.
        ClientTickEvents.END_CLIENT_TICK.register(MmsMetroClient::scanCars);

        // Editor packets arrive off-thread; open the screen on the client thread.
        ClientPlayNetworking.registerGlobalReceiver(MetroNetworking.OpenStationScreen.ID,
            (payload, context) -> context.client().execute(() ->
                context.client().setScreen(new StationEditScreen(payload))));
        ClientPlayNetworking.registerGlobalReceiver(MetroNetworking.OpenBumpScreen.ID,
            (payload, context) -> context.client().execute(() ->
                context.client().setScreen(new BumpEditScreen(payload))));
        ClientPlayNetworking.registerGlobalReceiver(MetroNetworking.OpenJunctionScreen.ID,
            (payload, context) -> context.client().execute(() ->
                context.client().setScreen(new JunctionEditScreen(payload))));
    }

    // Logs each loaded car's index, age, arc, position, chunk-loaded state, and removed flag, so a frozen car (age not advancing) is distinguishable from an unloaded chunk or a real removal.
    private static void scanCars(MinecraftClient client) {
        if (scanTick++ % 20 != 0) {
            return;
        }
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof MetroCarEntity car)) {
                continue;
            }
            int cx = car.getBlockPos().getX() >> 4;
            int cz = car.getBlockPos().getZ() >> 4;
            boolean chunkLoaded = world.getChunkManager().isChunkLoaded(cx, cz);
            MmsMetro.LOGGER.info(String.format(
                "[diag-scan] idx %d age %d arc %.3f pos (%.2f,%.2f,%.2f) chunkLoaded %b removed %b",
                car.getCarIndex(), car.clientAge(), car.getArcLength(),
                car.getX(), car.getY(), car.getZ(), chunkLoaded, car.isRemoved()));
        }
    }
}
