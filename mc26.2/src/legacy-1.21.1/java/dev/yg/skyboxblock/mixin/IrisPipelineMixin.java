package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Iris 1.8.1 adapter; hook a real completed final pass, not a potentially cancelled RETURN. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.IrisRenderingPipeline",remap=false)
public abstract class IrisPipelineMixin {
    @Inject(method="finalizeLevelRendering",at=@At(value="INVOKE",
            target="Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V",shift=At.Shift.AFTER),require=0,remap=false)
    private void skyboxblock$resolvedView(CallbackInfo ci) { SkyWorldRenderer.resolveIrisView(); }
}
