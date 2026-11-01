package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.custom.CustomSkyPack;
import dev.yg.skyboxblock.custom.CustomSkyRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import java.util.Optional;

/**
 * Owns the generated style pack object.
 *
 * <p>Fabric's built-in pack helper only reads packs shipped inside the mod jar, while these files are
 * generated next to the user's config folder, so {@code CustomSkyPackMixin} appends this pack to the
 * client repository instead. Building it lazily keeps the mixin cheap and tolerant of a failed scan.
 */
public final class CustomSkyPacks {
    public static final String PACK_ID = "skyboxblock_custom";
    private static volatile Pack pack;

    private CustomSkyPacks() { }

    public static void install() {
        // Touching the accessor is enough; the mixin appends the pack on every repository reload.
        markInstalled();
    }

    /** Called by the resource-manager mixin once the generated pack has been attached. */
    public static void markInstalled() { installed = true; }

    public static boolean installed() { return installed; }

    private static volatile boolean installed;

    public static synchronized Pack pack() {
        // Never cache a failure: the first repository reload can happen before the style scan has
        // produced the generated folder, and later reloads must be able to pick the pack up.
        if (pack != null) return pack;
        var generated = CustomSkyRegistry.generatedDir();
        if (generated == null || !generated.toFile().isDirectory()) {
            SkyboxBlockMod.LOGGER.info("[skyboxblock] pack: generated dir not ready ({})", generated);
            return null;
        }
        Optional<net.fabricmc.loader.api.ModContainer> container =
                FabricLoader.getInstance().getModContainer(SkyboxBlockMod.MOD_ID);
        if (container.isEmpty()) {
            SkyboxBlockMod.LOGGER.info("[skyboxblock] pack: mod container missing");
            return null;
        }
        ModMetadata metadata = container.get().getMetadata();
        PackLocationInfo location = new PackLocationInfo(PACK_ID,
                Component.literal("Sky Box Block custom styles"), PackSource.BUILT_IN, Optional.empty());
        PackResources resources = new CustomSkyPack(generated, metadata);
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override public PackResources openPrimary(PackLocationInfo info) { return resources; }
            @Override public PackResources openFull(PackLocationInfo info, Pack.Metadata ignored) { return resources; }
        };
        Pack created = Pack.readMetaAndCreate(location, supplier, PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(false, Pack.Position.TOP, false));
        if (created == null) {
            SkyboxBlockMod.LOGGER.warn("[skyboxblock] custom style pack could not be created");
            return null;
        }
        pack = created;
        return pack;
    }
}
