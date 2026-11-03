package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.SkyWindowRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rebuild project-owned programs/canvases after the native apply stage has loaded resource-pack textures. */
@Mixin(TextureManager.class)
abstract class SkyTextureReloadMixin {
    @Inject(method="lambda$reload$2",at=@At("RETURN"))
    private void skyboxblock$reload(CallbackInfo ci) {SkyWindowRenderer.invalidate();}
}
