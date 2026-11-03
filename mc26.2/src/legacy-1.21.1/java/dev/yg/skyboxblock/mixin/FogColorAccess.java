package dev.yg.skyboxblock.mixin;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to vanilla's already sampled biome/weather/dawn fog; never samples world RGB. */
@Mixin(FogRenderer.class)
public interface FogColorAccess {
    @Accessor("fogRed") static float skyboxblock$fogRed() { throw new AssertionError(); }
    @Accessor("fogGreen") static float skyboxblock$fogGreen() { throw new AssertionError(); }
    @Accessor("fogBlue") static float skyboxblock$fogBlue() { throw new AssertionError(); }
}
