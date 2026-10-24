package dev.yg.skyboxprobe.mixin;

import dev.yg.skyboxblock.client.SkyWorldRenderer;
import dev.yg.skyboxprobe.R30StateAudit;
import dev.yg.skyboxprobe.R30Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only present with the separate validation mod; normal production JAR is untouched. */
@Mixin(value=SkyWorldRenderer.class,remap=false)
public abstract class R30SkyTransactionsMixin {
    @Inject(method="drawOpaque",at=@At("HEAD"),remap=false)
    private static void skyboxprobe$beforeOpaque(CallbackInfo ci) { R30StateAudit.before("opaque"); }
    @Inject(method="drawOpaque",at=@At("RETURN"),remap=false)
    private static void skyboxprobe$afterOpaque(CallbackInfo ci) { R30StateAudit.after("opaque"); dev.yg.skyboxprobe.R34EdgeStages.capture("opaque"); }
    @Inject(method="resolveView",at=@At("HEAD"),remap=false)
    private static void skyboxprobe$beforeResolve(CallbackInfo ci) { R30StateAudit.before("resolve"); dev.yg.skyboxprobe.R34EdgeStages.capture("pre"); }
    @Inject(method="resolveView",at=@At("RETURN"),remap=false)
    private static void skyboxprobe$afterResolve(CallbackInfo ci) { R30StateAudit.after("resolve"); dev.yg.skyboxprobe.R34EdgeStages.capture("post"); }
    @Inject(method="release",at=@At("RETURN"),remap=false)
    private static void skyboxprobe$afterRelease(CallbackInfo ci) { R30Probe.afterSkyRelease(); }
}
