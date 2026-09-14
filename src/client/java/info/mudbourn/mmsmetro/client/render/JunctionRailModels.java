package info.mudbourn.mmsmetro.client.render;

import info.mudbourn.mmsmetro.block.JunctionBlock;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperUnbakedGroupedBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.function.Predicate;

// Retextures a vanilla rail that sits directly on a junction block, keeping the rail's own per-shape geometry and only swapping its sprite for the junction skin.
public final class JunctionRailModels {

    // The model whose one texture is stitched onto the block atlas and read back as the swap target.
    private static final Identifier JUNCTION_RAIL_MODEL = Identifier.of("mms_metro", "block/junction_rail");

    // Handle to the baked junction-rail model, so its atlas sprite can be fetched once the atlas exists.
    private static final ExtraModelKey<BlockStateModel> JUNCTION_RAIL_KEY =
        ExtraModelKey.create(JUNCTION_RAIL_MODEL::toString);

    // The junction skin's atlas sprite, resolved lazily on the first frame after baking.
    private static Sprite targetSprite;

    // Finds the source sprite of each vanilla rail quad so its UVs can be renormalised onto the junction sprite.
    private static SpriteFinder spriteFinder;

    // True once the sprite and finder are resolved; false-and-latched only if the atlas lookup itself fails.
    private static boolean resolved;

    private static boolean failed;

    private JunctionRailModels() {
    }

    // Registers the junction-rail model for baking and wraps the vanilla rail model so it can retexture over a junction.
    public static void register() {
        ModelLoadingPlugin.register(ctx -> {
            ctx.addModel(JUNCTION_RAIL_KEY, SimpleUnbakedExtraModel.blockStateModel(JUNCTION_RAIL_MODEL));
            ctx.modifyBlockModelOnLoad().register((model, context) -> {
                if (context.state().isOf(Blocks.RAIL)) {
                    return new UnbakedJunctionRail(model);
                }
                return model;
            });
        });
    }

    // Resolves the junction sprite and the atlas sprite finder once the block atlas has been stitched, retrying until the extra model is baked.
    private static boolean ensureSprite() {
        if (resolved) {
            return true;
        }
        if (failed) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        BlockStateModel baked = ((FabricBakedModelManager) client.getBakedModelManager()).getModel(JUNCTION_RAIL_KEY);
        if (baked == null) {
            return false;
        }
        Sprite sprite = baked.particleSprite();
        AbstractTexture atlas = client.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        if (sprite == null || !(atlas instanceof SpriteAtlasTexture blockAtlas)) {
            failed = true;
            return false;
        }
        targetSprite = sprite;
        spriteFinder = blockAtlas.spriteFinder();
        resolved = true;
        return true;
    }

    // Rewrites one quad's texture coordinates from its source sprite's atlas region onto the junction sprite's region, so the rail geometry is untouched and only the pixels change.
    private static void remapToTarget(QuadEmitter emitter, QuadView quad, Sprite source) {
        if (source == null) {
            return;
        }
        float srcU = source.getMinU();
        float srcV = source.getMinV();
        float srcW = source.getMaxU() - srcU;
        float srcH = source.getMaxV() - srcV;
        float dstU = targetSprite.getMinU();
        float dstV = targetSprite.getMinV();
        float dstW = targetSprite.getMaxU() - dstU;
        float dstH = targetSprite.getMaxV() - dstV;
        for (int i = 0; i < 4; i++) {
            float u = srcW == 0.0f ? 0.0f : (quad.u(i) - srcU) / srcW;
            float v = srcH == 0.0f ? 0.0f : (quad.v(i) - srcV) / srcH;
            emitter.uv(i, dstU + u * dstW, dstV + v * dstH);
        }
    }

    // The unbaked wrapper: bakes the vanilla rail model as usual, then wraps the result so it can retexture at render time.
    private static final class UnbakedJunctionRail extends WrapperUnbakedGroupedBlockStateModel {

        private UnbakedJunctionRail(BlockStateModel.UnbakedGrouped wrapped) {
            super(wrapped);
        }

        @Override
        public BlockStateModel bake(BlockState state, Baker baker) {
            return new BakedJunctionRail(super.bake(state, baker));
        }
    }

    // The baked wrapper: emits vanilla rail quads unchanged, unless the block below is a junction, in which case each quad is re-emitted with the junction sprite.
    private static final class BakedJunctionRail extends WrapperBlockStateModel {

        private BakedJunctionRail(BlockStateModel wrapped) {
            super(wrapped);
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockRenderView blockView, BlockPos pos, BlockState state,
                              Random random, Predicate<Direction> cullTest) {
            if (blockView.getBlockState(pos.down()).getBlock() instanceof JunctionBlock && ensureSprite()) {
                MutableMesh mesh = Renderer.get().mutableMesh();
                this.wrapped.emitQuads(mesh.emitter(), blockView, pos, state, random, cullTest);
                mesh.forEach(quad -> {
                    Sprite source = spriteFinder.find(quad);
                    emitter.copyFrom(quad);
                    remapToTarget(emitter, quad, source);
                    emitter.emit();
                });
                return;
            }
            this.wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);
        }
    }
}
