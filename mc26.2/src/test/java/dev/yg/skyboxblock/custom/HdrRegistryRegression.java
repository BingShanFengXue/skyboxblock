package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Real 26.2 custom registry and image import regressions, without a GL context or world. */
public final class HdrRegistryRegression {
    private static int checks;
    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name + " warnings=" + CustomSkyRegistry.warnings());
    }
    private static Path sky(Path game) { return game.resolve("config/skyboxblock/skyboxes"); }
    private static Path file(Path game, String name) { return sky(game).resolve(name); }
    private static Path out(Path game, String id) {
        return game.resolve("config/skyboxblock/generated/assets/skyboxblock/textures/sky/" + id + ".png");
    }
    private static String style(String key, String id, String picture) {
        return key + " = { id = \"skyboxblock:" + id + "\" picture = \"" + picture + "\" }\n";
    }
    private static Path game(Path root, String name, String config) throws Exception {
        Path game = root.resolve(name);
        Files.createDirectories(sky(game));
        Files.writeString(file(game, "01_case.conf"), config, StandardCharsets.UTF_8);
        return game;
    }
    private static void oversizedPngHeader(Path file, int width, int height) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(payload)) {
            data.writeInt(width); data.writeInt(height);
            data.writeByte(8); data.writeByte(2); data.writeByte(0); data.writeByte(0); data.writeByte(0);
        }
        byte[] fields = payload.toByteArray();
        CRC32 crc = new CRC32(); crc.update(type); crc.update(fields);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.write(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
            output.writeInt(fields.length); output.write(type); output.write(fields); output.writeInt((int)crc.getValue());
        }
    }

    private static void registryCases(Path root) throws Exception {
        Path mixed = game(root, "mixed-block-errors", "enable = true\n"
                + style("bad", "bad", "unsupported.gif") + style("good", "good", "tiny.png"));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(mixed, "tiny.png").toFile());
        CustomSkyRegistry.load(mixed);
        check(CustomSkyRegistry.styles().size() == 1
                && CustomSkyRegistry.styles().get(0).id().equals("skyboxblock:good"),
                "block-level error does not discard sibling style");

        Path invalidEnable = game(root, "invalid-enable", "enable = \"true\"\n" + style("good", "good", "tiny.png"));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(invalidEnable, "tiny.png").toFile());
        CustomSkyRegistry.load(invalidEnable);
        check(CustomSkyRegistry.styles().isEmpty(), "invalid enable rejects whole file");
        Path syntax = game(root, "syntax-error", "enable = true\ngood = { id = \"skyboxblock:good\" picture = \"tiny.png\"\n");
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(syntax, "tiny.png").toFile());
        CustomSkyRegistry.load(syntax);
        check(CustomSkyRegistry.styles().isEmpty(), "syntax error rejects whole file");

        Path fallback = game(root, "image-fallback", "enable = true\n"
                + style("missing", "missing_sky", "missing.png") + style("corrupt", "corrupt_sky", "corrupt.png"));
        Files.write(file(fallback, "corrupt.png"), new byte[]{1, 2, 3, 4});
        Files.deleteIfExists(out(fallback, "missing_sky"));
        Files.deleteIfExists(out(fallback, "corrupt_sky"));
        CustomSkyRegistry.load(fallback);
        check(CustomSkyRegistry.styles().size() == 2, "missing/corrupt images preserve registry IDs");
        for (String id : new String[]{"missing_sky", "corrupt_sky"}) {
            BufferedImage image = ImageIO.read(out(fallback, id).toFile());
            check(image != null && image.getWidth() == 512 && image.getHeight() == 256,
                    id + " generated 2:1 fallback");
            check(image.getRGB(0, 0) == 0xff808080, id + " uses neutral gray");
        }

        Path capped = root.resolve("style-limit");
        Files.createDirectories(sky(capped));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(capped, "tiny.png").toFile());
        StringBuilder first = new StringBuilder("enable = true\n");
        for (int i = 0; i < 64; i++) first.append(style("first_" + i, "limit_" + i, "tiny.png"));
        Files.writeString(file(capped, "01_first.conf"), first, StandardCharsets.UTF_8);
        Files.writeString(file(capped, "02_extra.conf"), "enable = true\n" + style("extra", "limit_64", "tiny.png"), StandardCharsets.UTF_8);
        Files.deleteIfExists(out(capped, "limit_64"));
        CustomSkyRegistry.load(capped);
        check(CustomSkyRegistry.styles().size() == 64, "strict style limit across conf files");
        check(!Files.exists(out(capped, "limit_64")), "65th style rejected before materialisation");
        check(CustomSkyRegistry.warnings().stream().anyMatch(w -> w.contains("上限 64")), "style limit warning emitted");
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("skybox-262-custom-registry-");
        Files.createDirectories(root);

        Path exr = game(root, "exr", "enable = true\n" + style("case", "test", "scene.exr"));
        Files.write(file(exr, "scene.exr"), OpenExrRegression.sampleExr(false, false));
        CustomSkyRegistry.load(exr);
        check(CustomSkyRegistry.styles().size() == 1, "EXR style");
        check(ImageIO.read(out(exr, "test").toFile()).getWidth() == 4, "EXR texture");

        Path exrTx = game(root, "exr-tx", "enable = true\n" + style("case", "test", "scene.tx"));
        Files.write(file(exrTx, "scene.tx"), OpenExrRegression.sampleExr(true, false));
        CustomSkyRegistry.load(exrTx);
        check(CustomSkyRegistry.styles().size() == 1, "OpenEXR TX style");
        check(ImageIO.read(out(exrTx, "test").toFile()).getHeight() == 2, "OpenEXR TX texture");

        Path png = game(root, "png", "enable = true\n" + style("case", "test", "scene.png"));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "PNG", file(png, "scene.png").toFile());
        CustomSkyRegistry.load(png);
        check(CustomSkyRegistry.styles().size() == 1 && ImageIO.read(out(png, "test").toFile()).getWidth() == 4,
                "normal PNG import");

        Path imageIoTx = game(root, "imageio-tx", "enable = true\n" + style("case", "test", "scene.tx"));
        Files.write(file(imageIoTx, "scene.tx"), Files.readAllBytes(file(png, "scene.png")));
        CustomSkyRegistry.load(imageIoTx);
        check(CustomSkyRegistry.styles().size() == 1 && ImageIO.read(out(imageIoTx, "test").toFile()).getHeight() == 2,
                "ImageIO TX import");

        Path jpg = game(root, "jpg", "enable = true\n" + style("case", "test", "scene.JPG"));
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "JPEG", file(jpg, "scene.JPG").toFile());
        Files.write(file(jpg, "scene.JPG"), new byte[17 * 1024 * 1024], java.nio.file.StandardOpenOption.APPEND);
        CustomSkyRegistry.load(jpg);
        check(CustomSkyRegistry.styles().size() == 1 && ImageIO.read(out(jpg, "test").toFile()).getWidth() == 4,
                "JPEG import beyond old byte limit");

        Path oversized = root.resolve("oversized/header.png");
        oversizedPngHeader(oversized, 100_000, 100_000);
        boolean rejected = false;
        try { PanoramaImageDecoder.decode(oversized, 4096, 2048); }
        catch (java.io.IOException expected) { rejected = expected.getMessage().contains("超过上限"); }
        check(rejected, "oversized PNG IHDR rejected before raster allocation");

        registryCases(root);
        System.out.println("PASS: " + checks + " 26.2 custom registry assertions");
    }
}
