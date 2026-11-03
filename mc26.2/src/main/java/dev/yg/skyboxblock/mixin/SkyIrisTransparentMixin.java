package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyWindowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve actual pack-composed no-depth-write transparency instead of painting an overlay over it. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.IrisRenderingPipeline",remap=false)
abstract class SkyIrisTransparentMixin {
    @Inject(method="beginTranslucents",at=@At("RETURN"),require=0)
    private void skyboxblock$afterDeferred(CallbackInfo ci) {SkyWindowRenderer.transparencyBoundary(false);}
    @Inject(method="finalizeLevelRendering",at=@At("HEAD"),require=0)
    private void skyboxblock$beforeComposite(CallbackInfo ci) {SkyWindowRenderer.transparencyBoundary(true);}
}
