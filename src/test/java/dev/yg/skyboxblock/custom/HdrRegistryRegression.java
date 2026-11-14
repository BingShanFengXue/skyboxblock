package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
        check(CustomSkyRegistry.styles().isEmpty(), "truncated HDR skipped");
        check(!CustomSkyRegistry.warnings().isEmpty(), "truncated HDR readable warning");
        check(!Files.exists(texture(truncated)), "no malformed texture generated");

        Path disguised = studio(root, "disguised", "png.hdr", true);
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(disguised, "png.hdr").toFile());
        CustomSkyRegistry.load(disguised);
        check(CustomSkyRegistry.styles().isEmpty(), "PNG masquerading as HDR rejected");
        Path huge = studio(root, "huge", "huge.hdr", true);
        Files.writeString(file(huge, "huge.hdr"), "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2048 +X 4097\n", StandardCharsets.US_ASCII);
        CustomSkyRegistry.load(huge);
        check(CustomSkyRegistry.styles().isEmpty(), "oversized dimensions rejected before allocation");

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
