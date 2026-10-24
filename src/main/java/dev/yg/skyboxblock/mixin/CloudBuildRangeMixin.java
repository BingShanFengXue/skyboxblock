package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyCloudRange;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Scoped native builder bounds. Ordinary world clouds always retain their original constants. */
@Mixin(LevelRenderer.class)
public abstract class CloudBuildRangeMixin {
    @ModifyConstant(method="buildClouds",constant=@Constant(intValue=-3),require=2,expect=2,allow=2)
    private int skyboxblock$privateCloudMinimum(int original) { return SkyCloudRange.minimumCell(original); }

    // The first int 4 is an unrelated local initializer; only the two actual loop bounds are selected.
    @ModifyConstant(method="buildClouds",constant={@Constant(intValue=4,ordinal=1),@Constant(intValue=4,ordinal=2)},require=2,expect=2,allow=2)
    private int skyboxblock$privateCloudMaximum(int original) { return SkyCloudRange.maximumCell(original); }
}
