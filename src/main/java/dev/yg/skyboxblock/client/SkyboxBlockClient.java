package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;

/** Registers only the independent vanilla sky and world-space renderer. */
public final class SkyboxBlockClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        BlockEntityRendererRegistry.register(SkyboxBlockMod.SKY_BLOCK_ENTITY, SkyBlockEntityRenderer::new);
        SkyboxServerState.register();
        if(ModCompat.DISTANT_HORIZONS) {
            try { DistantSkyDepth.register(); }
            catch(RuntimeException | LinkageError error) { SkyboxBlockMod.LOGGER.warn("[skyboxblock] optional DH render event adapter unavailable",error); }
        }
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            VanillaSkyScene.invalidate();
            context.register(SkyboxBlockMod.id("world_sky"), DefaultVertexFormat.POSITION, SkyWorldRenderer::setShader);
            context.register(SkyboxBlockMod.id("sky_resolve"), DefaultVertexFormat.POSITION, SkyWorldRenderer::setResolveShader);
            context.register(SkyboxBlockMod.id("sky_surface_composite"), DefaultVertexFormat.POSITION, SkySurfaceLayer::setShader);
            context.register(SkyboxBlockMod.id("sky_edge_history"), DefaultVertexFormat.POSITION, SkyTemporalEdgeMask::setShader);
            context.register(SkyboxBlockMod.id("sky_depth_marker"), DefaultVertexFormat.POSITION, SkyCoverage::setShader);
            context.register(SkyboxBlockMod.id("sky_material_background"), DefaultVertexFormat.POSITION, ItrpSkyBridge::setShader);
            context.register(SkyboxBlockMod.id("sky_meter_proxy"), DefaultVertexFormat.POSITION, SkyExposureMeter::setShader);
            context.register(SkyboxBlockMod.id("seam_diagnostic"), DefaultVertexFormat.POSITION, SeamScan::setShader);
            context.register(SkyboxBlockMod.id("vanilla_background"), DefaultVertexFormat.POSITION, VanillaSkyScene::setBackground);
            context.register(SkyboxBlockMod.id("vanilla_dawn"), DefaultVertexFormat.POSITION_COLOR, VanillaSkyScene::setDawn);
            context.register(SkyboxBlockMod.id("vanilla_celestial"), DefaultVertexFormat.POSITION_TEX, VanillaSkyScene::setCelestial);
            context.register(SkyboxBlockMod.id("vanilla_stars"), DefaultVertexFormat.POSITION, VanillaSkyScene::setStars);
            context.register(SkyboxBlockMod.id("vanilla_cloud"), DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, SkyVanillaClouds::setShader);
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(SkyWorldRenderer::release));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> SkyWorldRenderer.release());
        if (SeamScan.enabled()) SeamScan.register(); else RenderRegression.register();
        SkyboxBlockMod.LOGGER.info("[skyboxblock] independent vanilla simulation initialized ({})", ModCompat.describe());
    }
}
