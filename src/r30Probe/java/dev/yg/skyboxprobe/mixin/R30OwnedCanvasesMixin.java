package dev.yg.skyboxprobe.mixin;

import dev.yg.skyboxblock.client.VanillaSkyScene;
import dev.yg.skyboxprobe.R30GpuLifetime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe actual private target object transitions, including destroyed-object invalidation. */
@Mixin(value=VanillaSkyScene.class,remap=false)
public abstract class R30OwnedCanvasesMixin {
    @Inject(method="render(Ldev/yg/skyboxblock/client/SkyWorldRenderer$Frame;IIZ)I",at=@At("RETURN"),remap=false)
    private static void skyboxprobe$targets(CallbackInfoReturnable<Integer> ci) { R30GpuLifetime.observeTargets(); }
    @Inject(method="release",at=@At("RETURN"),remap=false)
    private static void skyboxprobe$releaseTargets(CallbackInfo ci) { R30GpuLifetime.observeTargets(); }
}
