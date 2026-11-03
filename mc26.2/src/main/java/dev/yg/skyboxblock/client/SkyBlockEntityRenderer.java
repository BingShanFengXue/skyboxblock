package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.yg.skyboxblock.SkyBlock;
import dev.yg.skyboxblock.SkyBlockEntity;
import dev.yg.skyboxblock.client.state.SkyBoxRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Extraction only: sky windows are not entities/materials in the world's lighting pipeline.
 * Geometry is consumed once, in the owning view's opaque pass, before translucent depth copies.
 * No per-vertex screen/equirectangular UVs: those are necessarily wrong under perspective/bob.
 */
public final class SkyBlockEntityRenderer implements BlockEntityRenderer<SkyBlockEntity, SkyBoxRenderState> {
    @Override public SkyBoxRenderState createRenderState() { return new SkyBoxRenderState(); }
    /** Match the 1.21.1 collector; the engine's default 64 would silently cull distant sky walls. */
    @Override public int getViewDistance() { return 256; }

    @Override public void extractRenderState(SkyBlockEntity entity, SkyBoxRenderState state, float partialTick,
                                             Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, cameraPos, crumbling);
        state.skin = !entity.isRemoved() && entity.getBlockState().getBlock() instanceof SkyBlock sky
                ? sky.skin() : null;
        state.north = entity.shouldRenderFace(Direction.NORTH);
        state.south = entity.shouldRenderFace(Direction.SOUTH);
        state.east = entity.shouldRenderFace(Direction.EAST);
        state.west = entity.shouldRenderFace(Direction.WEST);
        state.up = entity.shouldRenderFace(Direction.UP);
        state.down = entity.shouldRenderFace(Direction.DOWN);
    }

    @Override public void submit(SkyBoxRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                 CameraRenderState camera) {
        if (state.skin != null) SkyWindowRenderer.collect(state, pose.last().pose());
    }
}
