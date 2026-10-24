package dev.yg.skyboxfixture.mixin;

import dev.yg.skyboxfixture.PortalFixtureClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only camera/matrix metadata; never binds a target or issues a draw call. */
@Mixin(value = LevelRenderer.class, remap = true)
public abstract class FixtureLevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), remap = true)
    private void skyboxfixture$observe(DeltaTracker tracker, boolean outline, Camera camera,
                                     GameRenderer game, LightTexture light, Matrix4f view,
                                     Matrix4f projection, CallbackInfo ci) {
        PortalFixtureClient.observeWorldView(camera, view, projection);
    }

    @Inject(method="renderLevel",at=@At(value="CONSTANT",args="stringValue=destroyProgress"),remap=true)
    private void skyboxfixture$raster(CallbackInfo ci) { PortalFixtureClient.observeRaster(); }
}
