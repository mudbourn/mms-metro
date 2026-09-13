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
import net.minecraft.util.math.Vec3d;

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

    // Counts render-state builds so the render-path diagnostic fires occasionally rather than every frame.
    private static int diagFrames;

    @Override
    public void updateRenderState(MetroCarEntity entity, State state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.lerpedYaw = entity.getLerpedPathYaw(tickDelta);
        state.lerpedPitch = entity.getLerpedPathPitch(tickDelta);
        state.bodyOffsetX = 0.0;
        state.bodyOffsetY = 0.0;
        state.bodyOffsetZ = 0.0;
        Vec3d base = entity.getLerpedPos(tickDelta);
        boolean logNow = diagFrames++ % 300 == 0;
        // Draw a follower rigidly along the lead's trail rather than at its own lagging position, so the train never appears to tear from the seat of a rider on the fresh lead.
        if (entity.getCarIndex() > 0) {
            MetroCarEntity lead = entity.findLead();
            Vec3d target = null;
            double arcBack = 0.0;
            if (lead != null) {
                arcBack = lead.getArcLength() - entity.getArcLength();
                target = lead.trailPointBehind(arcBack);
                if (target != null) {
                    state.bodyOffsetX = target.x - base.x;
                    state.bodyOffsetY = target.y - base.y;
                    state.bodyOffsetZ = target.z - base.z;
                }
            }
            if (logNow) {
                double offset = target == null ? 0.0 : target.subtract(base).length();
                info.mudbourn.mmsmetro.MmsMetro.LOGGER.info(String.format(
                    "[diag-render] idx %d age %d isClient %b trail %d leadFound %b arcBack %.3f target %s offset %.3f base (%.2f,%.2f,%.2f)",
                    entity.getCarIndex(), entity.clientAge(), entity.getEntityWorld().isClient(),
                    entity.trailSize(), lead != null, arcBack,
                    target == null ? "NULL" : "ok", offset, base.x, base.y, base.z));
            }
        } else if (logNow) {
            info.mudbourn.mmsmetro.MmsMetro.LOGGER.info(String.format(
                "[diag-render] idx 0 (lead) age %d isClient %b trail %d base (%.2f,%.2f,%.2f)",
                entity.clientAge(), entity.getEntityWorld().isClient(),
                entity.trailSize(), base.x, base.y, base.z));
        }
    }

    @Override
    public void render(State state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera) {
        super.render(state, matrices, queue, camera);

        matrices.push();
        // Shift a follower from its own position onto the lead's trail before any rotation, while the matrix is still world-aligned.
        matrices.translate(state.bodyOffsetX, state.bodyOffsetY, state.bodyOffsetZ);
        // Orient about the rail pivot, lift the body onto it, then flip into the model's coordinate space.
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
        // World-space nudge from the car's own position onto the lead's trail; zero for the lead.
        public double bodyOffsetX;
        public double bodyOffsetY;
        public double bodyOffsetZ;
    }
}
