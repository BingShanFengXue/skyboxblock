package dev.yg.skyboxblock;

/** Stable block identities; ordinal is only a frame-local batch key, never saved to NBT. */
public enum SkyStyle {
    VANILLA("sky_block", 0, null),
    WHITE("white_sky_block", 1, null),
    BLACK("black_sky_block", 2, null),
    KLOPPENHEIM("kloppenheim_sky_block", 3, "kloppenheim"),
    SYFERFONTEIN("syferfontein_sky_block", 3, "syferfontein"),
    DUSK("dusk_sky_block", 3, "dusk"),
    GALAXY("galaxy_sky_block", 3, "galaxy"),
    LUSH("lush_sky_block", 3, "lush"),
    APOCALYPSE("apocalypse_sky_block", 3, "apocalypse");

    private final String blockId;
    private final int shaderMode;
    private final String panorama;
    SkyStyle(String blockId, int shaderMode, String panorama) {
        this.blockId = blockId;
        this.shaderMode = shaderMode;
        this.panorama = panorama;
    }
    public String blockId() { return blockId; }
    public int shaderMode() { return shaderMode; }
    public String panorama() { return panorama; }
}
