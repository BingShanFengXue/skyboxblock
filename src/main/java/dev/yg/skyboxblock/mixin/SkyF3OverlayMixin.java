package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyF3Diagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Add the client-only sky-window report to vanilla F3's system column. */
@Mixin(DebugScreenOverlay.class)
abstract class SkyF3OverlayMixin {
    @Inject(method = "getSystemInformation", at = @At("RETURN"), cancellable = true)
    private void skyboxblock$appendSkyDiagnostics(CallbackInfoReturnable<List<String>> cir) {
        List<String> original = cir.getReturnValue();
        List<String> lines = SkyF3Diagnostics.appendLines(Minecraft.getInstance(), original);
        if (lines != original) cir.setReturnValue(lines);
    }
}
