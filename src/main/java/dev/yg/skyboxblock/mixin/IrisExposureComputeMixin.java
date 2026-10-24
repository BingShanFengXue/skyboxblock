package dev.yg.skyboxblock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.yg.skyboxblock.client.SkyExposureMeter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Optional ITRP-only sampler scope; the original compute and its automatic exposure still run. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.program.ComputeProgram",remap=false)
public abstract class IrisExposureComputeMixin {
    @WrapMethod(method="dispatch",remap=false)
    private void skyboxblock$meter(float width,float height,Operation<Void> original) {
        Runnable restore=SkyExposureMeter.beforeCompute();
        try { original.call(width,height); }
        finally { if(restore!=null) restore.run(); }
    }
}
