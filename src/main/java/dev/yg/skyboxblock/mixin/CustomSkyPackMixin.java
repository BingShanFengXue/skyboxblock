package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.client.CustomSkyPacks;
import dev.yg.skyboxblock.custom.CustomSkyPack;
import dev.yg.skyboxblock.custom.CustomSkyRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds the generated custom-style pack to every client resource manager as it is constructed.
 *
 * <p>The pack repository keeps its selection as an immutable snapshot and exposes no API for adding
 * a pack from disk, while Fabric's built-in pack helper only reads packs shipped inside the mod jar.
 * Constructing the manager is therefore the one seam where the pack can join unconditionally.
 */
@Mixin(MultiPackResourceManager.class)
public class CustomSkyPackMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 2)
    private static List<PackResources> skyboxblock$appendCustomStylePack(List<PackResources> packs, PackType type) {
        if (type != PackType.CLIENT_RESOURCES) return packs;
        var generated = CustomSkyRegistry.generatedDir();
        if (generated == null || !generated.toFile().isDirectory()) return packs;
        var container = FabricLoader.getInstance().getModContainer(dev.yg.skyboxblock.SkyboxBlockMod.MOD_ID);
        if (container.isEmpty()) return packs;
        List<PackResources> extended = new ArrayList<>(packs);
        extended.add(new CustomSkyPack(generated, container.get().getMetadata()));
        CustomSkyPacks.markInstalled();
        return extended;
    }
}
