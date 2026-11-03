package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.Fixture262Client;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the real world's opaque-camera uniforms independently of SkyWindowRenderer. */
@Mixin(LevelRenderer.class)
public abstract class Fixture262LevelMixin {
    @Inject(method="lambda$addMainPass$0",at=@At(value="INVOKE",
            target="Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V",
            shift=At.Shift.AFTER))
    private void skyboxblock$fixtureOpaqueMatrices(CallbackInfo ci) {
        Fixture262Client.rememberIrisMatrices();
    }
}
