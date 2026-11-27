package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Runs the real registry image pipeline, without a GL context or world. */
public final class HdrRegistryRegression {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description + "; warnings=" + CustomSkyRegistry.warnings());
    }
    private static byte[] hdr() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < 8; i++) out.write(new byte[]{64, 96, (byte)128, (byte)130});
        return out.toByteArray();
    }
    private static Path studio(Path root, String name, String picture, boolean enabled) throws Exception {
        Path game = root.resolve(name);
        Path studio = game.resolve("config/skyboxblock/skyboxes");
        Files.createDirectories(studio);
        Files.writeString(studio.resolve("01_case.conf"), "enable = " + enabled + "\ncase = { id = \"skyboxblock:hdr_test\" picture = \"" + picture + "\" creative_inventory = true }\n", StandardCharsets.UTF_8);
        return game;
    }
    private static Path file(Path game, String name) { return game.resolve("config/skyboxblock/skyboxes").resolve(name); }
    private static Path texture(Path game) { return game.resolve("config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_test.png"); }
    private static Path generatedTexture(Path game, String id) {
        return game.resolve("config/skyboxblock/generated/assets/skyboxblock/textures/sky/" + id + ".png");
    }
    private static Path customGame(Path root, String name, String config) throws Exception {
        Path game = root.resolve(name);
        Path skyboxes = game.resolve("config/skyboxblock/skyboxes");
        Files.createDirectories(skyboxes);
        Files.writeString(skyboxes.resolve("01_case.conf"), config, StandardCharsets.UTF_8);
        return game;
    }
    private static String style(String key, String id, String picture) {
        return key + " = { id = \"skyboxblock:" + id + "\" picture = \"" + picture + "\" }\n";
    }
    /** A valid PNG signature and CRC-checked IHDR, with no image data/raster to allocate. */
    private static void oversizedPngHeader(Path file, int width, int height) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(chunk)) {
            data.writeInt(width); data.writeInt(height);
            data.writeByte(8); data.writeByte(2); data.writeByte(0); data.writeByte(0); data.writeByte(0);
        }
        byte[] fields = chunk.toByteArray();
        CRC32 crc = new CRC32(); crc.update(type); crc.update(fields);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.write(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
            out.writeInt(fields.length); out.write(type); out.write(fields); out.writeInt((int) crc.getValue());
        }
    }
    private static void customRegistryCases(Path root) throws Exception {
        Path mixed = customGame(root, "mixed-block-errors", "enable = true\n"
                + style("broken", "broken", "wrong.gif")
                + style("valid", "valid", "tiny.png"));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(mixed, "tiny.png").toFile());
        CustomSkyRegistry.load(mixed);
        check(CustomSkyRegistry.styles().size() == 1
                && CustomSkyRegistry.styles().get(0).id().equals("skyboxblock:valid"),
                "block semantic error does not discard sibling valid style");

        Path invalidEnable = customGame(root, "invalid-enable", "enable = \"true\"\n" + style("valid", "valid", "tiny.png"));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(invalidEnable, "tiny.png").toFile());
        CustomSkyRegistry.load(invalidEnable);
        check(CustomSkyRegistry.styles().isEmpty(), "invalid enable rejects whole file");
        Path syntax = customGame(root, "syntax-error", "enable = true\nvalid = { id = \"skyboxblock:valid\" picture = \"tiny.png\"\n");
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(syntax, "tiny.png").toFile());
        CustomSkyRegistry.load(syntax);
        check(CustomSkyRegistry.styles().isEmpty(), "syntax error rejects whole file");

        Path fallback = customGame(root, "image-fallback", "enable = true\n"
                + style("missing", "missing_sky", "missing.png")
                + style("corrupt", "corrupt_sky", "corrupt.png"));
        Files.write(file(fallback, "corrupt.png"), new byte[]{1, 2, 3, 4});
        Files.deleteIfExists(generatedTexture(fallback, "missing_sky"));
        Files.deleteIfExists(generatedTexture(fallback, "corrupt_sky"));
        CustomSkyRegistry.load(fallback);
        check(CustomSkyRegistry.styles().size() == 2, "missing/corrupt panorama preserves both IDs");
        for (String id : new String[]{"missing_sky", "corrupt_sky"}) {
            BufferedImage placeholder = ImageIO.read(generatedTexture(fallback, id).toFile());
            check(placeholder != null && placeholder.getWidth() == 512 && placeholder.getHeight() == 256,
                    id + " has generated 2:1 placeholder");
            check(placeholder.getRGB(0, 0) == 0xff808080, id + " placeholder is neutral grey");
        }

        Path capped = root.resolve("style-limit");
        Path skyboxes = capped.resolve("config/skyboxblock/skyboxes");
        Files.createDirectories(skyboxes);
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", skyboxes.resolve("tiny.png").toFile());
        StringBuilder first = new StringBuilder("enable = true\n");
        for (int i = 0; i < 64; i++) first.append(style("first_" + i, "limit_" + i, "tiny.png"));
        Files.writeString(skyboxes.resolve("01_first.conf"), first, StandardCharsets.UTF_8);
        Files.writeString(skyboxes.resolve("02_extra.conf"), "enable = true\n" + style("extra", "limit_64", "tiny.png"), StandardCharsets.UTF_8);
        Files.deleteIfExists(generatedTexture(capped, "limit_64"));
        CustomSkyRegistry.load(capped);
        check(CustomSkyRegistry.styles().size() == 64, "style cap is strict across conf files");
        check(!Files.exists(generatedTexture(capped, "limit_64")), "65th style is rejected before materialisation");
        check(CustomSkyRegistry.warnings().stream().anyMatch(w -> w.contains("上限 64")), "style cap emits one warning");
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("skybox-hdr-registry-");
        Files.createDirectories(root);
        Path valid = studio(root, "valid", "全景 HDR.HDR", true);
        byte[] input = hdr();
        Files.write(file(valid, "全景 HDR.HDR"), input);
        CustomSkyRegistry.load(valid);
        check(CustomSkyRegistry.styles().size() == 1, "HDR registered");
        check(CustomSkyRegistry.warnings().isEmpty(), "valid HDR without warnings");
        check(Arrays.equals(input, Files.readAllBytes(file(valid, "全景 HDR.HDR"))), "source bytes preserved");
        BufferedImage image = ImageIO.read(texture(valid).toFile());
        check(image != null && image.getWidth() == 4 && image.getHeight() == 2, "panorama dimensions");
        int expected = RadianceHdr.decode(input, 4096, 2048).image().getRGB(0, 0);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++)
            check(image.getRGB(x, y) == expected, "mapped RGB/opaque alpha retained");
        Path icon = valid.resolve("config/skyboxblock/generated/assets/skyboxblock/textures/block/hdr_test.png");
        check(ImageIO.read(icon.toFile()).getWidth() == 32, "block icon generated");
        String manifest = CustomSkyRegistry.manifestHash();
        byte[] png = Files.readAllBytes(texture(valid));
        CustomSkyRegistry.load(valid);
        check(manifest.equals(CustomSkyRegistry.manifestHash()), "stable registry manifest");
        check(Arrays.equals(png, Files.readAllBytes(texture(valid))), "deterministic generated texture");

        Path disabled = studio(root, "disabled", "missing.hdr", false);
        CustomSkyRegistry.load(disabled);
        check(CustomSkyRegistry.styles().isEmpty(), "disabled style not registered");
        check(CustomSkyRegistry.warnings().isEmpty(), "disabled missing HDR not decoded");
        Path truncated = studio(root, "truncated", "cut.hdr", true);
        Files.write(file(truncated, "cut.hdr"), Arrays.copyOf(input, input.length - 1));
        CustomSkyRegistry.load(truncated);
        check(CustomSkyRegistry.styles().size() == 1, "truncated HDR keeps its custom ID registered");
        check(!CustomSkyRegistry.warnings().isEmpty(), "truncated HDR readable warning");
        BufferedImage truncatedPlaceholder = ImageIO.read(texture(truncated).toFile());
        check(truncatedPlaceholder != null && truncatedPlaceholder.getWidth() == 512
                        && truncatedPlaceholder.getHeight() == 256,
                "truncated HDR is replaced with neutral placeholder");

        Path disguised = studio(root, "disguised", "png.hdr", true);
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(disguised, "png.hdr").toFile());
        CustomSkyRegistry.load(disguised);
        check(CustomSkyRegistry.styles().size() == 1, "PNG masquerading as HDR keeps the style ID");
        check(ImageIO.read(texture(disguised).toFile()) != null, "disguised HDR uses generated placeholder");
        Path huge = studio(root, "huge", "huge.hdr", true);
        Files.writeString(file(huge, "huge.hdr"), "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2048 +X 4097\n", StandardCharsets.US_ASCII);
        CustomSkyRegistry.load(huge);
        check(CustomSkyRegistry.styles().size() == 1, "oversized HDR keeps its custom ID registered");
        BufferedImage hugePlaceholder = ImageIO.read(texture(huge).toFile());
        check(hugePlaceholder != null && hugePlaceholder.getWidth() == 512 && hugePlaceholder.getHeight() == 256,
                "oversized HDR is rejected before decode and uses placeholder");

        Path pngGame = studio(root, "png", "old.png", true);
        Files.write(file(pngGame, "old.png"), png);
        CustomSkyRegistry.load(pngGame);
        check(CustomSkyRegistry.styles().size() == 1, "existing PNG import preserved");
        check(Arrays.equals(png, Files.readAllBytes(texture(pngGame))), "constant PNG import bytes unchanged");

        Path exrGame = studio(root, "exr", "scene.exr", true);
        Files.write(file(exrGame, "scene.exr"), OpenExrRegression.sampleExr(false, false));
        CustomSkyRegistry.load(exrGame);
        check(CustomSkyRegistry.styles().size() == 1, "EXR registered through registry");
        check(ImageIO.read(texture(exrGame).toFile()).getWidth() == 4, "EXR texture generated");
        Path txGame = studio(root, "tx", "scene.tx", true);
        Files.write(file(txGame, "scene.tx"), OpenExrRegression.sampleExr(true, false));
        CustomSkyRegistry.load(txGame);
        check(CustomSkyRegistry.styles().size() == 1, "TX OpenEXR registered through registry");
        check(ImageIO.read(texture(txGame).toFile()).getHeight() == 2, "TX texture generated");
        Path jpgGame = studio(root, "jpg", "scene.JPG", true);
        BufferedImage jpgSource = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) jpgSource.setRGB(x, y, 0xff000000 | (x * 60 << 16) | (y * 100 << 8) | 40);
        ImageIO.write(jpgSource, "JPEG", file(jpgGame, "scene.JPG").toFile());
        Files.write(file(jpgGame, "scene.JPG"), new byte[17 * 1024 * 1024], java.nio.file.StandardOpenOption.APPEND);
        CustomSkyRegistry.load(jpgGame);
        check(CustomSkyRegistry.styles().size() == 1, "JPG larger than old 16MiB cap registered");
        check(ImageIO.read(texture(jpgGame).toFile()).getWidth() == 4, "JPG texture generated");

        Path imageIoTx = studio(root, "imageio-tx", "renamed.tx", true);
        Files.write(file(imageIoTx, "renamed.tx"), png);
        CustomSkyRegistry.load(imageIoTx);
        check(CustomSkyRegistry.styles().size() == 1, "ImageIO TX style registered");
        check(ImageIO.read(texture(imageIoTx).toFile()).getWidth() == 4, "ImageIO TX decoded after dimension preflight");

        Path hugePng = root.resolve("oversized-png/header.png");
        oversizedPngHeader(hugePng, 100_000, 100_000);
        boolean oversizedRejected = false;
        try { PanoramaImageDecoder.decode(hugePng, 4096, 2048); }
        catch (java.io.IOException expectedSizeError) {
            oversizedRejected = expectedSizeError.getMessage().contains("超过上限");
        }
        check(oversizedRejected, "oversized PNG header rejected before read/allocation");

        customRegistryCases(root);

        if (args.length > 1) {
            Path real = studio(root, "real4k", "real.hdr", true);
            Files.copy(Path.of(args[1]), file(real, "real.hdr"));
            long start = System.nanoTime();
            CustomSkyRegistry.load(real);
            long millis = (System.nanoTime() - start) / 1_000_000;
            check(CustomSkyRegistry.styles().size() == 1, "real 4K HDR larger than old 16MiB cap imported");
            BufferedImage panorama = ImageIO.read(texture(real).toFile());
            check(panorama.getWidth() == 4096 && panorama.getHeight() == 2048, "4K dimensions retained");
            System.out.println("real4kRegistryLoadMs=" + millis + " generated=" + texture(real).toAbsolutePath());
        }
        String report = "PASS: " + checks + " registry assertions\n";
        Files.writeString(root.resolve("result.txt"), report, StandardCharsets.UTF_8);
        System.out.print(report);
    }
}
