package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyDistantDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH 3.3.3's observed per-view projection; optional, no dependency on its classes at compile time. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.core.render.renderer.LodRenderer",remap=false)
abstract class SkyDhRenderMixin {
    @Inject(method="render(Lcom/seibel/distanthorizons/core/render/RenderParams;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",at=@At("HEAD"),require=0)
    private void skyboxblock$projection(@Coerce Object params,@Coerce Object profiler,CallbackInfo ci) {SkyDistantDepth.capture(params);}
}
