package info.mudbourn.mmsmetro.client;

import info.mudbourn.mmsmetro.client.render.MetroCarEntityRenderer;
import info.mudbourn.mmsmetro.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class MmsMetroClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.METRO_CAR, MetroCarEntityRenderer::new);
    }
}
