package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.yg.skyboxblock.SkyBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Collection only; the world-stage renderer owns transforms, depth and drawing. */
public final class SkyBlockEntityRenderer implements BlockEntityRenderer<SkyBlockEntity> {
    public SkyBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(SkyBlockEntity block, float partial, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
        SkyWorldRenderer.collect(block);
    }
    @Override public int getViewDistance() { return 256; }
}
