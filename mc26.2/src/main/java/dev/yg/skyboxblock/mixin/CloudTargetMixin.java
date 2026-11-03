package dev.yg.skyboxblock.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.yg.skyboxblock.client.SkyRendererHost;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把云画进自绘天空帧。
 *
 * <p>{@code CloudRenderer.render} 里把目标硬编码成 {@code GameRenderer.mainRenderTarget()}；
 * 1.21.1 的抓帧方案天然带云，26.2 自绘则要显式重定向一次。
 * 这个 redirect 只在 {@link SkyRendererHost#cloudTargetForPass()} 返回非空时生效
 * （也就是我们主动再画一遍云的时候），平时完全不影响原版流程。
 */
@Mixin(CloudRenderer.class)
public class CloudTargetMixin {
    private static boolean logged;

    @Redirect(method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget skyboxblock$redirectCloudTarget(GameRenderer gameRenderer) {
        RenderTarget override = SkyRendererHost.cloudTargetForPass();
        if (override != null && !logged) {
            logged = true;
            dev.yg.skyboxblock.SkyboxBlockMod.LOGGER.info("[skyboxblock] 云重绘 redirect 命中（目标已重定向）");
        }
        return override != null ? override : gameRenderer.mainRenderTarget();
    }
}
