package dev.yg.skyboxblock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.yg.skyboxblock.client.SkyWindowRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
abstract class SkyGameRenderMixin {
    @WrapMethod(method="renderLevel")
    private void skyboxblock$gameView(DeltaTracker delta,Operation<Void> original) {
        SkyWindowRenderer.beginGame();
        try {original.call(delta);} finally {SkyWindowRenderer.endGame();}
    }

    @ModifyArg(method="renderLevel",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),index=0)
    private Matrix4f skyboxblock$projection(Matrix4f actual) {return SkyWindowRenderer.captureProjection(actual);}
}
