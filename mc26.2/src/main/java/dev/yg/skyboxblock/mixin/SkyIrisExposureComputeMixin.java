package dev.yg.skyboxblock.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.yg.skyboxblock.client.ItrpDarkMeterProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Installed Iris 1.11.4 bytecode: use() binds samplers/images, dispatch(FF)V only barriers/dispatches.
 * The real compute, workgroup count, automatic exposure and image output are entirely unchanged. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.program.ComputeProgram",remap=false)
public abstract class SkyIrisExposureComputeMixin {
    @WrapMethod(method="dispatch(FF)V",remap=false)
    private void skyboxblock$darkMeter(float width,float height,Operation<Void> original) {
        Runnable restore=ItrpDarkMeterProxy.beforeCompute(this);
        try {original.call(width,height);}
        finally {if(restore!=null)restore.run();}
    }
}
