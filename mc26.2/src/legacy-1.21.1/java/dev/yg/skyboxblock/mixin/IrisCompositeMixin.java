package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.ItrpSkyBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Iris 1.8.1 graphics-pass boundary; no Iris classes enter our compile-time signatures. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
public abstract class IrisCompositeMixin {
    // The real pass has already bound its FBO, program, samplers and custom uniforms here.
    // Compute-only passes do not call renderQuad and deliberately do not enter this bridge.
    @Inject(method = "renderAll()V", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pathways/FullScreenQuadRenderer;renderQuad()V"),
            remap = false, require = 0)
    private void skyboxblock$beforeCompositeQuad(CallbackInfo ci) {
        ItrpSkyBridge.beforeCompositeQuad(this);
    }
}
