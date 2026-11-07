package dev.yg.skyboxblock;

import net.minecraft.resources.ResourceLocation;

/**
 * Identity of one sky-window surface. Built-in styles come from {@link SkyStyle}; custom styles come
 * from the `config/skyboxblock/skyboxes` folder and are registered at start-up, so they must not be
 * modelled as enum constants.
 *
 * <p>Every surface has a nonnegative shader material identity. Built-ins retain their stable enum
 * ordinals; custom IDs occupy a separate range. Coverage stores material + 1, reserving zero for
 * empty pixels, so a rear custom batch cannot claim a different front surface.
 */
public record SkySkin(String id, int shaderMode, ResourceLocation texture, int material) {
    private static final int CUSTOM_START = SkyStyle.values().length;
    private static final java.util.Map<String, Integer> CUSTOM_MATERIALS = new java.util.LinkedHashMap<>();
    public static SkySkin of(SkyStyle style) {
        return new SkySkin(SkyboxBlockMod.MOD_ID + ":" + style.blockId(), style.shaderMode(),
                style.panorama() == null ? null : SkyboxBlockMod.id("textures/sky/" + style.panorama() + ".png"),
                style.ordinal());
    }

    /** Panoramic custom style: shaderMode 3 samples {@code texture} through the equirect mapping. */
    public static synchronized SkySkin custom(String id, ResourceLocation texture) {
        int material = CUSTOM_MATERIALS.computeIfAbsent(id,
                ignored -> Math.addExact(CUSTOM_START, CUSTOM_MATERIALS.size()));
        return new SkySkin(id, 3, texture, material);
    }

    public boolean isCustom() { return material >= CUSTOM_START; }

    /** Styles whose sky is dark enough that the exposure meter must be corrected. */
    public boolean darkSky() {
        return id.equals(SkyboxBlockMod.MOD_ID + ":black_sky_block")
                || id.equals(SkyboxBlockMod.MOD_ID + ":galaxy_sky_block")
                || id.equals(SkyboxBlockMod.MOD_ID + ":apocalypse_sky_block");
    }
}
