package dev.yg.skyboxblock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.yg.skyboxblock.client.RenderRegression;
import dev.yg.skyboxblock.client.SeamScan;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invocation-scoped lifetime also covers cancellation and extra cross-portal views. */
@Mixin(value=GameRenderer.class,priority=1100)
public abstract class GameRendererMixin {
    @WrapMethod(method="renderLevel")
    private void skyboxblock$view(DeltaTracker delta,Operation<Void> original) {
        SkyWorldRenderer.beginFrame();
        boolean completed=false;
        try {
            RenderRegression.lockCamera();
            if (SeamScan.isActive()) SeamScan.lockCamera();
            original.call(delta);
            completed=true;
        } finally {
            SkyWorldRenderer.endFrame(completed);
        }
    }

    @Inject(method="renderItemInHand",at=@At("HEAD"))
    private void skyboxblock$beforeNativeHand(CallbackInfo ci) { RenderRegression.beforeNativeHands(); }
    @Inject(method="renderItemInHand",at=@At("RETURN"))
    private void skyboxblock$afterNativeHand(CallbackInfo ci) { RenderRegression.afterNativeHands(); }
}
