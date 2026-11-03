package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyWindowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris 1.11.4 no longer calls renderQuad: use the audited real indexed draw boundary. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.CompositeRenderer",remap=false)
abstract class SkyIrisCompositeMixin {
    @Inject(method="renderAll",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V",shift=At.Shift.BEFORE),require=0)
    private void skyboxblock$sceneInput(CallbackInfo ci) {SkyWindowRenderer.beforeIrisComposite(this);}
}
