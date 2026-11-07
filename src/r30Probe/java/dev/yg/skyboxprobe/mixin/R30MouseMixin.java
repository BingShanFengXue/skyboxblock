package dev.yg.skyboxprobe.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class R30MouseMixin {
    @Inject(method="grabMouse", at=@At("HEAD"), cancellable=true)
    private void skyboxprobe$noGrab(CallbackInfo ci) {
        if (Boolean.getBoolean("skyboxblock.r30.enabled")) ci.cancel();
    }
}
