package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.RegressionFluidControl;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explicit fixture-only water scheduling control; no ServerLevel.tick interception. */
@Mixin(ServerLevel.class)
public abstract class RegressionFluidMixin {
    @Inject(method="tickFluid",at=@At("HEAD"),cancellable=true)
    private void skyboxblock$fixtureFluid(BlockPos pos,Fluid fluid,CallbackInfo ci) {
        if (RegressionFluidControl.suppress((ServerLevel)(Object)this,pos,fluid)) ci.cancel();
    }
}
