package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyWindowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Append to the vanilla right column before it lays out the text and its backgrounds. */
@Mixin(DebugScreenOverlay.class)
abstract class SkyDebugOverlayMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Font font;
    @Shadow public abstract boolean showDebugScreen();

    @Inject(method="extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V", at=@At("HEAD"))
    private void skyboxblock$appendDiagnostics(GuiGraphicsExtractor graphics, List<String> lines,
                                                boolean left, CallbackInfo ci) {
        if(left || minecraft.level==null || !minecraft.debugEntries.isOverlayVisible() || !showDebugScreen())return;
        int width=Math.max(1,graphics.guiWidth()-4);
        for(String line:SkyWindowRenderer.diagnosticsLines(minecraft.level)) {
            if(font.width(line)>width)line=font.plainSubstrByWidth(line,Math.max(0,width-font.width("...")))+"...";
            lines.add(line);
        }
    }
}
