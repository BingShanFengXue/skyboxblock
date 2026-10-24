package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.FixtureClientControl;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional settled-fixture water animation control; production ticker is returned unchanged. */
@Mixin(SpriteContents.class)
public abstract class RegressionWaterTextureMixin {
    @Inject(method="createTicker",at=@At("RETURN"),cancellable=true)
    private void skyboxblock$staticFixtureWater(CallbackInfoReturnable<SpriteTicker> ci) {
        SpriteTicker original=ci.getReturnValue();
        SpriteTicker result=FixtureClientControl.wrapWater((SpriteContents)(Object)this,original);
        if(result!=original) ci.setReturnValue(result);
    }
}
