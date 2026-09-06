package info.mudbourn.mmsmetro.client.render;

import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.MinecartEntityModel;
import net.minecraft.client.render.entity.state.MinecartEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

// Draws a metro car with the vanilla minecart model, posed from our own yaw and pitch.
public class MetroCarEntityRenderer extends EntityRenderer<MetroCarEntity, MetroCarEntityRenderer.State> {

    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/minecart.png");

    private final MinecartEntityModel model;

    public MetroCarEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);

        this.shadowRadius = 0.7f;
        this.model = new MinecartEntityModel(ctx.getPart(EntityModelLayers.MINECART));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void updateRenderState(MetroCarEntity entity, State state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.lerpedYaw = entity.getLerpedPathYaw(tickDelta);
        state.lerpedPitch = entity.getLerpedPathPitch(tickDelta);
    }

    @Override
    public void render(State state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera) {
        super.render(state, matrices, queue, camera);

        matrices.push();
        // Match the vanilla minecart: orient about the rail pivot, then lift the
        // body onto it, and flip into the model's coordinate space.
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0f - state.lerpedYaw));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(state.lerpedPitch));
        matrices.translate(0.0f, 0.375f, 0.0f);
        matrices.scale(-1.0f, -1.0f, 1.0f);

        queue.submitModel(
            this.model,
            state,
            matrices,
            this.model.getLayer(TEXTURE),
            state.light,
            OverlayTexture.DEFAULT_UV,
            state.outlineColor,
            null
        );

        matrices.pop();
    }

    public static class State extends MinecartEntityRenderState {
    }
}
