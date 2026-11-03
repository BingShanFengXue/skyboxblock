package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.RenderRegression;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void skyboxblock$begin(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game,
                                   LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        SkyWorldRenderer.beginView((LevelRenderer) (Object) this, camera, view, projection,
                delta.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V", shift = At.Shift.AFTER))
    private void skyboxblock$skyEnvironment(CallbackInfo ci) { SkyWorldRenderer.captureSkyEnvironment(); }

    @Inject(method = "renderSky", at = @At("RETURN"))
    private void skyboxblock$nativeSkyReference(CallbackInfo ci) { RenderRegression.nativeSkySnapshot(); }

    // Opaque BE batches are flushed here. Iris renders hands later at "translucent".
    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=destroyProgress"))
    private void skyboxblock$opaque(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game,
                                    LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        SkyWorldRenderer.drawOpaque();
    }

    // Fixture-only foreground evidence. Outline vertices are deferred until these real batch flushes.
    @Inject(method = "renderLevel", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V")})
    private void skyboxblock$beforeOverlayBatch(CallbackInfo ci) { RenderRegression.beforeWorldOverlay(); }

    @Inject(method = "renderLevel", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V", shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V", shift = At.Shift.AFTER)})
    private void skyboxblock$afterOverlayBatch(CallbackInfo ci) { RenderRegression.afterWorldOverlay(); }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void skyboxblock$beforeWeather(CallbackInfo ci) { RenderRegression.beforeWorldOverlay(); }

    @Inject(method = "renderSnowAndRain", at = @At("RETURN"))
    private void skyboxblock$afterWeather(CallbackInfo ci) { RenderRegression.afterWorldOverlay(); }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void skyboxblock$end(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game,
                                 LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        SkyWorldRenderer.endView();
    }
}
