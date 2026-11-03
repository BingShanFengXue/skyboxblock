package dev.yg.skyboxblock.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancel only the opt-in integration fixture's cursor grab; ordinary gameplay is unchanged. */
@Mixin(MouseHandler.class)
public abstract class Fixture262MouseMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void skyboxblock$fixtureNoMouseGrab(CallbackInfo ci) {
        if (!System.getProperty("skyboxblock.fixture.control", "").isBlank()) ci.cancel();
    }
}
