package dev.yg.skyboxblock.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Borrow immutable, resource-pack-reloaded native cloud cells; never invoke CloudRenderer.render. */
@Mixin(CloudRenderer.class)
public interface SkyCloudDataAccess {
    @Accessor("texture") CloudRenderer.TextureData skyboxblock$texture();
}
