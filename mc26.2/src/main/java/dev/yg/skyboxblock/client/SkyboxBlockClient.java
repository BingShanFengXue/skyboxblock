package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * 26.2 客户端入口。
 *
 * <p>26.2 使用独立 GL 场景及视图内 opaque/final 合成；原生阶段 mixin 负责几何、深度和
 * Iris/DH 观察，不在客户端事件里重入原版天空渲染器。此入口负责：
 * <ol>
 *   <li>把天空盒方块的 BE 渲染器注册上（{@link BlockEntityRenderers#register}）；</li>
 *   <li>安装自定义天空盒的资源包（mixin 注入 {@code MultiPackResourceManager}）；</li>
 *   <li>客户端命令 {@code /skyboxcustom}。</li>
 * </ol>
 */
public final class SkyboxBlockClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        BlockEntityRenderers.register(SkyboxBlockMod.SKY_BLOCK_ENTITY, context -> new SkyBlockEntityRenderer());
        SkyboxServerState.register();
        CustomSkyCommand.register();
        CustomSkyPacks.install();
        // The native opaque/final view mixins own rendering. Never draw a second SkyRenderer here:
        // Iris hooks change global pipeline phases even when the destination is a private target.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> SkyHosts.get().close());
        SkyboxBlockMod.LOGGER.info("[skyboxblock] 26.2 client initialized (isolated per-view opaque/final sky path)");
        ModCompat.logEnvironment();
    }
}
