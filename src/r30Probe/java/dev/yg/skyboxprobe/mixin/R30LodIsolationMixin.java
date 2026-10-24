package dev.yg.skyboxprobe.mixin;

import dev.yg.skyboxprobe.R30Probe;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Causal owned fixture only: omit vanilla chunk drawing, retain live DH and sky block entities. */
@Mixin(LevelRenderer.class)
public abstract class R30LodIsolationMixin {
    @Inject(method="renderSectionLayer",at=@At("HEAD"),cancellable=true)
    private void skyboxprobe$ownedLodOnlyTerrain(CallbackInfo ci) {
        if(R30Probe.suppressVanillaTerrain()) ci.cancel();
    }
}
