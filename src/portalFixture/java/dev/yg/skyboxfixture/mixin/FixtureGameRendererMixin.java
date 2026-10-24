package dev.yg.skyboxfixture.mixin;

import dev.yg.skyboxfixture.PortalFixtureClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes invocation nesting; captures only after the complete root world/portal composition. */
@Mixin(value = GameRenderer.class, remap = true, priority = 100)
public abstract class FixtureGameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), remap = true)
    private void skyboxfixture$begin(DeltaTracker tracker, CallbackInfo ci) {
        PortalFixtureClient.beginGameView();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), remap = true, order = 2000)
    private void skyboxfixture$end(DeltaTracker tracker, CallbackInfo ci) {
        PortalFixtureClient.endGameView();
    }
}
