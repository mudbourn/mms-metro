package info.mudbourn.mmsmetro.client;

import info.mudbourn.mmsmetro.client.render.MetroCarEntityRenderer;
import info.mudbourn.mmsmetro.client.screen.BumpEditScreen;
import info.mudbourn.mmsmetro.client.screen.StationEditScreen;
import info.mudbourn.mmsmetro.network.MetroNetworking;
import info.mudbourn.mmsmetro.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

public class MmsMetroClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.METRO_CAR, MetroCarEntityRenderer::new);
        HudRenderCallback.EVENT.register((context, tickCounter) ->
            MetroHud.render(context, MinecraftClient.getInstance()));

        // Editor packets arrive off-thread; open the screen on the client thread.
        ClientPlayNetworking.registerGlobalReceiver(MetroNetworking.OpenStationScreen.ID,
            (payload, context) -> context.client().execute(() ->
                context.client().setScreen(new StationEditScreen(payload))));
        ClientPlayNetworking.registerGlobalReceiver(MetroNetworking.OpenBumpScreen.ID,
            (payload, context) -> context.client().execute(() ->
                context.client().setScreen(new BumpEditScreen(payload))));
    }
}
