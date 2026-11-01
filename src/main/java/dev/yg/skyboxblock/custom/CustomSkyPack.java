package dev.yg.skyboxblock.custom;

import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Serves the files generated for custom sky styles straight from disk.
 *
 * <p>The whole custom-style feature therefore uses only ordinary pack files: a blockstate, two
 * models, the panorama texture and the language entries. Nothing is injected into the vanilla
 * registry beyond the blocks themselves, and the image never has to live inside the jar.
 */
public final class CustomSkyPack implements ModResourcePack {
    private final Path root;
    private final ModMetadata metadata;
    private final net.minecraft.server.packs.PackLocationInfo location;

    public CustomSkyPack(Path root, ModMetadata metadata) {
        this.root = root;
        this.metadata = metadata;
        this.location = new net.minecraft.server.packs.PackLocationInfo("skyboxblock/custom",
                net.minecraft.network.chat.Component.literal("Sky Box Block custom styles"),
                net.minecraft.server.packs.repository.PackSource.BUILT_IN, java.util.Optional.empty());
    }

    @Override public ModMetadata getFabricModMetadata() { return metadata; }

    @Override public net.minecraft.server.packs.PackLocationInfo location() { return location; }

    /** No overlay directories are produced for generated styles. */
    @Override public ModResourcePack createOverlay(String overlay) { return this; }

    @Override public IoSupplier<InputStream> getRootResource(String... segments) {
        Path path = root;
        for (String segment : segments) path = path.resolve(segment);
        Path resolved = path;
        return Files.isRegularFile(resolved) ? () -> Files.newInputStream(resolved) : null;
    }

    @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
        Path path = file(type, id);
        return path != null && Files.isRegularFile(path) ? () -> Files.newInputStream(path) : null;
    }

    @Override public void listResources(PackType type, String namespace, String prefix,
                                       PackResources.ResourceOutput output) {
        Path base = root.resolve(type.getDirectory()).resolve(namespace).resolve(prefix);
        if (!Files.isDirectory(base)) return;
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String rel = root.resolve(type.getDirectory()).relativize(path).toString().replace('\\', '/');
                output.accept(ResourceLocation.fromNamespaceAndPath(namespace, rel.substring(namespace.length() + 1)),
                        () -> Files.newInputStream(path));
            });
        } catch (IOException ignored) {
            // A partially written directory must never break the whole resource reload.
        }
    }

    @Override public Set<String> getNamespaces(PackType type) {
        Path base = root.resolve(type.getDirectory());
        Set<String> namespaces = new LinkedHashSet<>();
        if (!Files.isDirectory(base)) return namespaces;
        try (Stream<Path> list = Files.list(base)) {
            list.filter(Files::isDirectory).forEach(path -> namespaces.add(path.getFileName().toString()));
        } catch (IOException ignored) {
            // Same rule as listResources: report what exists, never throw during a reload.
        }
        return namespaces;
    }

    /**
     * Minecraft reads the pack metadata through this hook, not through the raw file, so the
     * generated {@code pack.mcmeta} is parsed here; returning null makes the pack unusable.
     */
    @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        Path meta = root.resolve("pack.mcmeta");
        if (!Files.isRegularFile(meta)) return null;
        try {
            com.google.gson.JsonObject document = com.google.gson.JsonParser.parseString(
                    Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return document.has("pack") ? serializer.fromJson(document.getAsJsonObject("pack")) : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    @Override public String packId() { return "skyboxblock/custom-sky"; }

    @Override public void close() { }

    private Path file(PackType type, ResourceLocation id) {
        return root.resolve(type.getDirectory()).resolve(id.getNamespace()).resolve(id.getPath());
    }
}
