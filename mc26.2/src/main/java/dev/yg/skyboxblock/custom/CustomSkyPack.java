package dev.yg.skyboxblock.custom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Serves the files generated for custom sky styles straight from disk.
 *
 * <p>直接实现 {@link PackResources}（不继承 {@code AbstractPackResources}）：
 * 那套基类在 26.3 已经被删除，而 {@code PackResources} 在 26.1/26.2/26.3 要求的成员集合一致
 * （location / getRootResource / getResource / listResources / getNamespaces / getMetadataSection / close），
 * 所以一份实现三版通吃。metadata 自己从 {@code pack.mcmeta} 解析。
 */
public final class CustomSkyPack implements PackResources {
    private final Path root;
    private final PackLocationInfo location;

    public CustomSkyPack(Path root, PackLocationInfo location) {
        this.root = root;
        this.location = location;
    }

    @Override public PackLocationInfo location() { return location; }

    @Override public IoSupplier<InputStream> getRootResource(String... segments) {
        Path path = root;
        for (String segment : segments) path = path.resolve(segment);
        Path resolved = path;
        return Files.isRegularFile(resolved) ? () -> Files.newInputStream(resolved) : null;
    }

    @Override public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        Path path = root.resolve(type.getDirectory()).resolve(id.getNamespace()).resolve(id.getPath());
        return Files.isRegularFile(path) ? () -> Files.newInputStream(path) : null;
    }

    @Override public void listResources(PackType type, String namespace, String prefix,
                                       PackResources.ResourceOutput output) {
        Path base = root.resolve(type.getDirectory()).resolve(namespace).resolve(prefix);
        if (!Files.isDirectory(base)) return;
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String rel = root.resolve(type.getDirectory()).relativize(path).toString().replace('\\', '/');
                output.accept(Identifier.fromNamespaceAndPath(namespace, rel.substring(namespace.length() + 1)),
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
     * 生成的 {@code pack.mcmeta} 由这里解析：26.2 的 {@code MetadataSectionType} 与 26.3 一致，
     * 都用 {@code codec().parse(JsonOps.INSTANCE, json)}。
     */
    @Override public <T> T getMetadataSection(MetadataSectionType<T> type) {
        IoSupplier<InputStream> supplier = getRootResource("pack.mcmeta");
        if (supplier == null) return null;
        try (InputStream in = supplier.get()) {
            JsonObject document = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!document.has("pack")) return null;
            return type.codec().parse(JsonOps.INSTANCE, document.getAsJsonObject("pack")).result().orElse(null);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    @Override public void close() { }
}
