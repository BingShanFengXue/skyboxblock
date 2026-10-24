package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.RenderRegression;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Actual type-state span covers vanilla BufferSource AND Iris's segmented buffer renderer. */
@Mixin(RenderStateShard.class)
public abstract class BufferSourceMixin {
    @Inject(method = "setupRenderState", at = @At("RETURN"))
    private void skyboxblock$beforeLines(CallbackInfo ci) {
        if ((Object)this instanceof RenderType type) RenderRegression.beforeLines(type);
    }

    @Inject(method = "clearRenderState", at = @At("HEAD"))
    private void skyboxblock$afterLines(CallbackInfo ci) {
        if ((Object)this instanceof RenderType type) RenderRegression.afterLines(type);
    }
}
