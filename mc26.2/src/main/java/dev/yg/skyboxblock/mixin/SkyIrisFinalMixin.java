package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyWindowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Iris 1.11.4 hook. Pseudo means no hard Iris linkage when the mod is absent. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.FinalPassRenderer",remap=false)
abstract class SkyIrisFinalMixin {
    @Inject(method="renderFinalPass",at=@At("HEAD"),require=0)
    private void skyboxblock$beforeFinal(CallbackInfo ci) {SkyWindowRenderer.beforeIrisFinal();}
    @Inject(method="renderFinalPass",at=@At("RETURN"),require=0)
    private void skyboxblock$afterFinal(CallbackInfo ci) {SkyWindowRenderer.afterIrisFinal();}
}
