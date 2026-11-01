package dev.yg.skyboxblock.custom;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads {@code config/skyboxblock/skyboxes}, validates every style and materialises the pack files
 * that make the style usable. Everything here runs once, during mod start-up, because blocks have to
 * be registered before the registry freezes.
 *
 * <p>The mod never overwrites an existing file: the two example panoramas and the example config are
 * written only when missing, so a hand-edited studio folder survives every update.
 */
public final class CustomSkyRegistry {
    public static final String FOLDER = "skyboxes";
    private static final String EXAMPLE_CONFIG = "00_example.conf";
    private static final int MAX_FILES = 64;
    private static final int MAX_STYLES = 64;
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_IMAGE_WIDTH = 4096;
    private static final int MAX_IMAGE_HEIGHT = 2048;

    /** One usable custom style: a registered block id plus where its panorama lives. */
    public record Style(String id, String picture, boolean creative, Map<String, String> names,
                        ResourceLocation panorama, String displayName) { }

    private static Path baseDir;
    private static Path generatedDir;
    private static final List<Style> STYLES = new ArrayList<>();
    private static final List<String> WARNINGS = new ArrayList<>();
    private static String manifestHash = "";

    private CustomSkyRegistry() { }

    public static Path baseDir() { return baseDir; }
    public static Path generatedDir() { return generatedDir; }
    public static List<Style> styles() { return List.copyOf(STYLES); }
    public static List<String> warnings() { return List.copyOf(WARNINGS); }

    /** Hash of what actually affects the registry: the sorted id + picture pairs. */
    public static String manifestHash() { return manifestHash; }

    /** Called from {@code SkyboxBlockMod.onInitialize}; never throws. */
    public static void load(Path gameDir) {
        STYLES.clear();
        WARNINGS.clear();
        baseDir = gameDir.resolve("config").resolve("skyboxblock").resolve(FOLDER);
        generatedDir = gameDir.resolve("config").resolve("skyboxblock").resolve("generated");
        try {
            Files.createDirectories(baseDir);
            writeExamples();
            Files.createDirectories(generatedDir);
            // Minecraft refuses a pack without metadata, and the pack is a plain folder on disk.
            write(generatedDir.resolve("pack.mcmeta"),
                    "{\n  \"pack\": {\n    \"pack_format\": 34,\n"
                            + "    \"description\": \"Sky Box Block generated custom styles\"\n  }\n}\n");
        } catch (IOException | RuntimeException error) {
            WARNINGS.add("无法准备自定义天空盒目录: " + error.getMessage());
            manifestHash = hash(List.of());
            return;
        }
        List<Path> configs = new ArrayList<>();
        try (Stream<Path> list = Files.list(baseDir)) {
            list.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".conf"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_FILES)
                    .forEach(configs::add);
        } catch (IOException error) {
            WARNINGS.add("无法列出目录 " + baseDir + ": " + error.getMessage());
        }
        Map<String, Style> byId = new LinkedHashMap<>();
        for (Path config : configs) {
            SkyConfigParser.Result result = readConfig(config);
            WARNINGS.addAll(result.warnings());
            WARNINGS.addAll(result.errors());
            if (!result.usable() || !result.enabled()) continue;
            for (SkyConfigParser.StyleSpec spec : result.styles()) {
                if (!buildStyle(config.getFileName().toString(), spec, byId)) continue;
                if (byId.size() >= MAX_STYLES) {
                    WARNINGS.add("风格数量超过上限 " + MAX_STYLES + "，其余已忽略");
                    break;
                }
            }
        }
        STYLES.addAll(byId.values());
        List<String> manifest = STYLES.stream().map(style -> style.id() + "|" + style.picture()).toList();
        manifestHash = hash(manifest);
        for (String warning : WARNINGS) SkyboxBlockMod.LOGGER.warn("[skyboxblock] {}", warning);
        if (!STYLES.isEmpty())
            SkyboxBlockMod.LOGGER.info("[skyboxblock] custom sky styles: {} (manifest {})",
                    STYLES.size(), manifestHash.substring(0, Math.min(12, manifestHash.length())));
    }

    private static SkyConfigParser.Result readConfig(Path config) {
        String name = config.getFileName().toString();
        try {
            if (Files.size(config) > MAX_FILE_BYTES)
                return new SkyConfigParser.Result(false, List.of(), List.of(), List.of(name + ": 文件超过 64 KB，已跳过"));
            return SkyConfigParser.parse(name, Files.readString(config, StandardCharsets.UTF_8));
        } catch (IOException error) {
            return new SkyConfigParser.Result(false, List.of(), List.of(), List.of(name + ": 读取失败 " + error.getMessage()));
        }
    }

    private static boolean buildStyle(String fileName, SkyConfigParser.StyleSpec spec, Map<String, Style> byId) {
        if (byId.containsKey(spec.id())) {
            WARNINGS.add(fileName + ": 重复的 id " + spec.id() + "，已忽略后出现的定义");
            return false;
        }
        Path picture = baseDir.resolve(spec.picture());
        if (!Files.isRegularFile(picture)) {
            WARNINGS.add(fileName + ": 找不到图片 " + spec.picture() + "，已跳过 " + spec.id());
            return false;
        }
        try {
            if (Files.size(picture) > MAX_IMAGE_BYTES) {
                WARNINGS.add(fileName + ": 图片 " + spec.picture() + " 超过 16 MB，已跳过 " + spec.id());
                return false;
            }
        } catch (IOException error) {
            WARNINGS.add(fileName + ": 无法读取图片 " + spec.picture() + "：" + error.getMessage());
            return false;
        }
        ResourceLocation panorama;
        try {
            panorama = materialise(spec.id(), picture);
        } catch (IOException | RuntimeException error) {
            WARNINGS.add(fileName + ": 处理图片 " + spec.picture() + " 失败：" + error.getMessage());
            return false;
        }
        if (panorama == null) return false;
        String name = SkyConfigParser.displayName(spec, "zh_cn");
        byId.put(spec.id(), new Style(spec.id(), spec.picture(), spec.creativeInventory(),
                Map.copyOf(spec.names()), panorama, name));
        return true;
    }

    /**
     * Copies the panorama into the generated pack with a soft wrap seam, and writes the pack files
     * that describe the block. Returns the texture id, or {@code null} when the image is unusable.
     */
    private static ResourceLocation materialise(String styleId, Path picture) throws IOException {
        ResourceLocation id = ResourceLocation.parse(styleId);
        String ns = id.getNamespace();
        String path = id.getPath();
        BufferedImage image;
        try (InputStream input = Files.newInputStream(picture)) {
            image = ImageIO.read(input);
        }
        if (image == null) {
            WARNINGS.add("图片无法解析（需要 PNG）：" + picture.getFileName());
            return null;
        }
        if (image.getWidth() > MAX_IMAGE_WIDTH || image.getHeight() > MAX_IMAGE_HEIGHT) {
            WARNINGS.add("图片超过 4096x2048：" + picture.getFileName());
            return null;
        }
        if (Math.abs(image.getWidth() - image.getHeight() * 2) > image.getHeight() / 4)
            WARNINGS.add("图片不是 2:1 等距柱状，按全景强行映射：" + picture.getFileName());
        BufferedImage wrapped = featherWrap(image);
        Path textureFile = generatedDir.resolve("assets").resolve(ns).resolve("textures").resolve("sky")
                .resolve(path + ".png");
        Files.createDirectories(textureFile.getParent());
        ImageIO.write(wrapped, "PNG", textureFile.toFile());
        writeBlockFiles(ns, path, wrapped);
        return ResourceLocation.fromNamespaceAndPath(ns, "textures/sky/" + path + ".png");
    }

    /** Blends the left/right edges so a hand-made panorama cannot show a vertical seam. */
    private static BufferedImage featherWrap(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int band = Math.max(1, Math.min(8, width / 64));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) row[x] = source.getRGB(x, y);
            for (int i = 0; i < band; i++) {
                double t = (i + 1.0) / (band + 1.0);
                int left = row[i];
                int right = row[width - 1 - i];
                row[i] = blend(left, right, t);
                row[width - 1 - i] = blend(right, left, t);
            }
            out.setRGB(0, y, width, 1, row, 0, width);
        }
        return out;
    }

    private static int blend(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) Math.round(ar * (1 - t) + br * t);
        int g = (int) Math.round(ag * (1 - t) + bg * t);
        int bl = (int) Math.round(ab * (1 - t) + bb * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static void writeBlockFiles(String namespace, String path, BufferedImage panorama) throws IOException {
        Path assets = generatedDir.resolve("assets").resolve(namespace);
        write(assets.resolve("blockstates").resolve(path + ".json"),
                "{\n  \"variants\": {\n    \"\": { \"model\": \"" + namespace + ":block/" + path + "\" }\n  }\n}\n");
        write(assets.resolve("models/block").resolve(path + ".json"),
                "{\n  \"textures\": { \"all\": \"" + namespace + ":block/" + path + "\" },\n"
                        + "  \"elements\": [\n    { \"from\": [0,0,0], \"to\": [16,16,16],\n"
                        + "      \"faces\": {\n"
                        + "        \"down\": { \"texture\": \"#all\" }, \"up\": { \"texture\": \"#all\" },\n"
                        + "        \"north\": { \"texture\": \"#all\" }, \"south\": { \"texture\": \"#all\" },\n"
                        + "        \"west\": { \"texture\": \"#all\" }, \"east\": { \"texture\": \"#all\" }\n"
                        + "      } }\n  ]\n}\n");
        write(assets.resolve("models/item").resolve(path + ".json"),
                "{\n  \"parent\": \"" + namespace + ":block/" + path + "\"\n}\n");
        Path icon = assets.resolve("textures/block").resolve(path + ".png");
        Files.createDirectories(icon.getParent());
        ImageIO.write(iconOf(panorama), "PNG", icon.toFile());
    }

    /** 32x32 block icon: the centre of the panorama, so the item looks like its own sky. */
    private static BufferedImage iconOf(BufferedImage panorama) {
        int size = Math.min(panorama.getWidth(), panorama.getHeight());
        int x = (panorama.getWidth() - size) / 2;
        int y = (panorama.getHeight() - size) / 2;
        BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < 32; py++) {
            for (int px = 0; px < 32; px++) {
                int sx = x + px * size / 32;
                int sy = y + py * size / 32;
                icon.setRGB(px, py, panorama.getRGB(sx, sy));
            }
        }
        return icon;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String hash(List<String> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (RuntimeException | java.security.NoSuchAlgorithmException error) {
            return "unavailable";
        }
    }

    /** Two deterministic panorama examples plus the documented example config; never overwritten. */
    private static void writeExamples() throws IOException {
        writeExampleImage(baseDir.resolve("example_white.png"), 0xFFFFFFFF);
        writeExampleImage(baseDir.resolve("example_black.png"), 0xFF000000);
        Path config = baseDir.resolve(EXAMPLE_CONFIG);
        if (Files.exists(config)) return;
        write(config, """
                # 由 skyboxblock 生成的示例，可自由修改；已存在的文件不会被覆盖。
                # enable 改为 true 才会启用自定义天空盒，改完需要重启游戏。
                enable = false

                example_white = {
                    id = "skyboxblock:example_white"
                    picture = "example_white.png"
                    creative_inventory = true
                    lang_zh_cn = { name = "示例·纯白" }
                    lang_en_us = { name = "Example White" }
                }

                example_black = {
                    id = "skyboxblock:example_black"
                    picture = "example_black.png"
                    creative_inventory = true
                    lang_zh_cn = { name = "示例·纯黑" }
                    lang_en_us = { name = "Example Black" }
                }
                """);
    }

    private static void writeExampleImage(Path file, int argb) throws IOException {
        if (Files.exists(file)) return;
        BufferedImage image = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 512; y++)
            for (int x = 0; x < 1024; x++) image.setRGB(x, y, argb);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "PNG", file.toFile());
    }
}
