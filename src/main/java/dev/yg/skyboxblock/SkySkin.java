package dev.yg.skyboxblock;

import net.minecraft.resources.ResourceLocation;

/**
 * Identity of one sky-window surface. Built-in styles come from {@link SkyStyle}; custom styles come
 * from the `config/skyboxblock/skyboxes` folder and are registered at start-up, so they must not be
 * modelled as enum constants.
 *
 * <p>{@code material} keeps the shader-side @{code SkyMaterial} uniform meaningful for the built-in
 * styles (their enum ordinal is referenced by the regression negative control); custom styles pass
 * {@code -1} because they are identified by their texture instead.
 */
public record SkySkin(String id, int shaderMode, ResourceLocation texture, int material) {
    public static SkySkin of(SkyStyle style) {
        return new SkySkin(SkyboxBlockMod.MOD_ID + ":" + style.blockId(), style.shaderMode(),
                style.panorama() == null ? null : SkyboxBlockMod.id("textures/sky/" + style.panorama() + ".png"),
                style.ordinal());
    }

    /** Panoramic custom style: shaderMode 3 samples {@code texture} through the equirect mapping. */
    public static SkySkin custom(String id, ResourceLocation texture) {
        return new SkySkin(id, 3, texture, -1);
    }

    public boolean isCustom() { return material < 0; }

    /** Styles whose sky is dark enough that the exposure meter must be corrected. */
    public boolean darkSky() {
        return id.equals(SkyboxBlockMod.MOD_ID + ":black_sky_block")
                || id.equals(SkyboxBlockMod.MOD_ID + ":galaxy_sky_block")
                || id.equals(SkyboxBlockMod.MOD_ID + ":apocalypse_sky_block");
    }
}
