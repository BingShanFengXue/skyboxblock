package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.Fixture262Client;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A final-display screenshot, not an Iris pre-composite intermediate target. */
@Mixin(GameRenderer.class)
public abstract class Fixture262RendererMixin {
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
    private org.joml.Matrix4f skyboxblock$fixtureProjection(org.joml.Matrix4f projection) {
        Fixture262Client.rememberProjection(projection);
        return projection;
    }
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"), index = 4)
    private org.joml.Matrix4fc skyboxblock$fixtureView(org.joml.Matrix4fc view) {
        Fixture262Client.rememberView(view);
        return view;
    }
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void skyboxblock$fixtureFinalFrame(CallbackInfo ci) {
        Fixture262Client.afterFinalFrame();
    }
}
