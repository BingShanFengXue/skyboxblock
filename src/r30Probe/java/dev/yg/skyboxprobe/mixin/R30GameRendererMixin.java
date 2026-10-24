package dev.yg.skyboxprobe.mixin;

import dev.yg.skyboxprobe.R30Probe;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Separate opt-in fixture only: after complete root rendering, independent of hidden HUD. */
@Mixin(GameRenderer.class)
public abstract class R30GameRendererMixin {
    @Inject(method="render", at=@At("TAIL"))
    private void skyboxprobe$r30Frame(CallbackInfo ci) { R30Probe.afterFrame(); }
}
