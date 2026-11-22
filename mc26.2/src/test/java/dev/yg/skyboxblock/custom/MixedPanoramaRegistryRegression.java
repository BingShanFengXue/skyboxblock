package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exercises both styles in one configuration file through the real startup registry. */
public final class MixedPanoramaRegistryRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + "; warnings=" + CustomSkyRegistry.warnings());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 4)
            throw new IllegalArgumentException("Expected output directory and optionally original CONF, HDR, EXR paths");
        boolean real = args.length == 4;
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        Path game = Files.createTempDirectory(root, real ? "original-" : "synthetic-");
        Path studio = game.resolve("config/skyboxblock/skyboxes");
        Files.createDirectories(studio);
        if (real) {
            for (int i = 1; i < args.length; i++) {
                Path input = Path.of(args[i]);
                Files.copy(input, studio.resolve(input.getFileName()));
            }
        } else {
            Files.writeString(studio.resolve("test.conf"), """
                    enable = true
                    test1 = {
                        id = "skyboxblock:test1"
                        picture = "test.hdr"
                        creative_inventory = true
                        lang_zh_cn = { name = "测试1" }
                        lang_en_us = { name = "test1" }
                    }
                    test2 = {
                        id = "skyboxblock:test2"
                        picture = "test.exr"
                        creative_inventory = true
                        lang_zh_cn = { name = "测试2" }
                        lang_en_us = { name = "test2" }
                    }
                    """, StandardCharsets.UTF_8);
            byte[] header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n".getBytes(StandardCharsets.US_ASCII);
            byte[] hdr = java.util.Arrays.copyOf(header, header.length + 4 * 8);
            for (int i = header.length; i < hdr.length; i += 4) {
                hdr[i] = 64; hdr[i + 1] = 96; hdr[i + 2] = (byte) 128; hdr[i + 3] = (byte) 130;
            }
            Files.write(studio.resolve("test.hdr"), hdr);
            Files.write(studio.resolve("test.exr"), OpenExrRegression.fixture("wide-piz"));
        }
        CustomSkyRegistry.load(game);
        check(CustomSkyRegistry.styles().stream().map(CustomSkyRegistry.Style::id).toList()
                .equals(List.of("skyboxblock:test1", "skyboxblock:test2")), "Both custom styles must register");
        // The synthetic wide fixture is deliberately not a panorama; only its aspect-ratio warning is expected.
        check(real ? CustomSkyRegistry.warnings().isEmpty()
                : CustomSkyRegistry.warnings().size() == 1 && CustomSkyRegistry.warnings().getFirst().contains("不是 2:1"),
                "No unexpected import warning");
        Path textures = game.resolve("config/skyboxblock/generated/assets/skyboxblock/textures");
        for (int number = 1; number <= 2; number++) {
            String name = "test" + number;
            var style = CustomSkyRegistry.styles().get(number - 1);
            check(style.creative(), name + " creative inventory flag");
            check(style.names().equals(Map.of("zh_cn", "测试" + number, "en_us", name)), name + " localized names");
            var panorama = ImageIO.read(textures.resolve("sky/" + name + ".png").toFile());
            var icon = ImageIO.read(textures.resolve("block/" + name + ".png").toFile());
            int width = number == 1 ? (real ? 4000 : 4) : (real ? 3072 : 1048);
            int height = number == 1 ? (real ? 2000 : 2) : (real ? 1536 : 32);
            check(panorama != null && panorama.getWidth() == width && panorama.getHeight() == height, name + " output dimensions");
            check(icon != null && icon.getWidth() == 32 && icon.getHeight() == 32, name + " block icon");
            System.out.printf("%s %s -> %dx%d panorama + 32x32 icon%n", style.id(), style.picture(), width, height);
        }
        System.out.println("PASS: mixed HDR/PIZ registry; warnings=" + CustomSkyRegistry.warnings().size() + " output=" + game.toAbsolutePath());
    }
}
