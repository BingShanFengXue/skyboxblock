package dev.yg.skyboxblock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.yg.skyboxblock.client.SkyWindowRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scoped around all Iris RETURN hooks as well; cancellation/portal recursion cannot leak views. */
@Mixin(LevelRenderer.class)
abstract class SkyLevelRenderMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @WrapMethod(method="render")
    private void skyboxblock$view(GraphicsResourceAllocator allocator,DeltaTracker delta,boolean outline,
                                 CameraRenderState camera,Matrix4fc view,GpuBufferSlice fog,Vector4f fogColor,
                                 boolean sky,Operation<Void> original) {
        SkyWindowRenderer.beginLevel(levelRenderState,view,delta.getGameTimeDeltaPartialTick(false));
        try {original.call(allocator,delta,outline,camera,view,fog,fogColor,sky);}
        finally {SkyWindowRenderer.endLevel();}
    }

    @Inject(method="lambda$addMainPass$0",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V",shift=At.Shift.AFTER))
    private void skyboxblock$opaque(CallbackInfo ci) {SkyWindowRenderer.drawOpaque();}

    @Inject(method="lambda$addMainPass$0",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent()V"))
    private void skyboxblock$nativeBeforeTransparent(CallbackInfo ci) {
        if(!dev.yg.skyboxblock.client.ModCompat.irisActive())SkyWindowRenderer.transparencyBoundary(false);
    }
    @Inject(method="lambda$addMainPass$0",at=@At("RETURN"))
    private void skyboxblock$nativeAfterTransparent(CallbackInfo ci) {
        if(!dev.yg.skyboxblock.client.ModCompat.irisActive())SkyWindowRenderer.transparencyBoundary(true);
    }
}
