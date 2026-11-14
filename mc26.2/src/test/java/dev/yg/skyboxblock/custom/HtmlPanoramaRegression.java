package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Exercises HTML opt-in, registry conversion, visual output and cache invalidation. */
public final class HtmlPanoramaRegression {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message + "; warnings=" + CustomSkyRegistry.warnings());
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("skybox-html-regression-");
        parserAndDisabledMode(root);
        boolean browserTest = Arrays.asList(args).contains("--browser");
        if (browserTest) runBrowserIntegration(root);
        System.out.println("PASS: " + checks + " HTML panorama assertions" + (browserTest ? " (Chromium integration)" : ""));
    }

    private static void parserAndDisabledMode(Path root) throws Exception {
        check(HtmlPanoramaRenderer.isHtml(Path.of("page.html")), ".html recognized");
        check(HtmlPanoramaRenderer.isHtml(Path.of("page.HTM")), ".htm is case insensitive");
        check(!HtmlPanoramaRenderer.isHtml(Path.of("page.png")), "non-HTML extension ignored");
        for (String extension : new String[]{".html", ".htm"}) {
            SkyConfigParser.Result parsed = SkyConfigParser.parse("html.conf",
                    "sky = { id = \"skyboxblock:html_test\" picture = \"page" + extension + "\" }\n");
            check(parsed.usable() && parsed.styles().size() == 1, extension + " accepted by style config");
        }

        Path game = createGame(root.resolve("disabled"), "page.html");
        Path source = game.resolve("config/skyboxblock/skyboxes/page.html");
        writePage(source, "#123456", "#dca864");
        String previous = System.getProperty(HtmlPanoramaRenderer.ENABLE_PROPERTY);
        String previousServer = System.getProperty("skyboxblock.test.dedicatedServer");
        try {
            System.setProperty(HtmlPanoramaRenderer.ENABLE_PROPERTY, "false");
            System.setProperty("skyboxblock.test.dedicatedServer", "false");
            CustomSkyRegistry.load(game);
            check(CustomSkyRegistry.styles().size() == 1, "disabled HTML still preserves registry identity");
            check(CustomSkyRegistry.warnings().stream().anyMatch(w -> w.contains("disabled")),
                    "disabled import reports the enable switch");
            check(Files.isRegularFile(source), "disabled import preserves source page");

            Path serverGame = createGame(root.resolve("server"), "server-only.html");
            System.setProperty(HtmlPanoramaRenderer.ENABLE_PROPERTY, "true");
            System.setProperty("skyboxblock.test.dedicatedServer", "true");
            CustomSkyRegistry.load(serverGame);
            check(CustomSkyRegistry.styles().size() == 1, "server keeps the HTML style registry entry");
            check(CustomSkyRegistry.warnings().stream().anyMatch(w -> w.contains("dedicated server")),
                    "server uses a placeholder without a local page or browser");
            check(!Files.exists(serverGame.resolve("config/skyboxblock/skyboxes/server-only.html")),
                    "server does not require client HTML source");
        } finally {
            restore(HtmlPanoramaRenderer.ENABLE_PROPERTY, previous);
            restore("skyboxblock.test.dedicatedServer", previousServer);
        }
    }

    private static void runBrowserIntegration(Path root) throws Exception {
        String previousEnabled = System.getProperty(HtmlPanoramaRenderer.ENABLE_PROPERTY);
        String previousWidth = System.getProperty("skyboxblock.html.width");
        String previousWait = System.getProperty("skyboxblock.html.waitMs");
        try {
            System.setProperty(HtmlPanoramaRenderer.ENABLE_PROPERTY, "true");
            System.setProperty("skyboxblock.html.width", "4096");
            System.setProperty("skyboxblock.html.waitMs", "1000");
            Path game = createGame(root.resolve("enabled"), "panorama.html");
            Path page = game.resolve("config/skyboxblock/skyboxes/panorama.html");
            writePage(page, "#123456", "#dca864");
            byte[] original = Files.readAllBytes(page);

            long start = System.nanoTime();
            CustomSkyRegistry.load(game);
            long loadMs = (System.nanoTime() - start) / 1_000_000;
            check(CustomSkyRegistry.styles().size() == 1, "HTML page registers as a custom sky style");
            Path texture = game.resolve("config/skyboxblock/generated/assets/skyboxblock/textures/sky/html_test.png");
            BufferedImage image = ImageIO.read(texture.toFile());
            check(image != null && image.getWidth() == 4096 && image.getHeight() == 2048,
                    "browser output retains the full 2:1 panorama viewport");
            check(image.getRGB(1024, 1024) == 0xff123456, "JavaScript canvas pixels survive HTML capture");
            check(image.getRGB(3072, 1024) == 0xffdca864, "second panorama region survives HTML capture");
            check(Arrays.equals(original, Files.readAllBytes(page)), "capture leaves source HTML unchanged");

            Path cache = game.resolve("config/skyboxblock/generated/html-cache");
            HtmlPanoramaRenderer.Rendered hit = HtmlPanoramaRenderer.render(page, cache, 4096, 2048);
            check(hit.cacheHit(), "unchanged HTML reuses its generated screenshot");
            check(Arrays.equals(original, Files.readAllBytes(page)), "cache hit leaves source HTML unchanged");

            Files.writeString(page.resolveSibling("palette.css"),
                    ":root{--left:#654321;--right:#a864dc}\n/* revision two */\n", StandardCharsets.UTF_8);
            HtmlPanoramaRenderer.Rendered refreshed = HtmlPanoramaRenderer.render(page, cache, 4096, 2048);
            check(!refreshed.cacheHit(), "relative CSS change invalidates the screenshot cache");
            check(refreshed.image().getRGB(1024, 1024) == 0xff654321, "relative CSS is loaded and re-rendered");
            Files.writeString(page, Files.readString(page, StandardCharsets.UTF_8) + "<!-- revision two -->\n",
                    StandardCharsets.UTF_8);
            HtmlPanoramaRenderer.Rendered pageRefresh = HtmlPanoramaRenderer.render(page, cache, 4096, 2048);
            check(!pageRefresh.cacheHit(), "HTML content change invalidates the screenshot cache");
            check(pageRefresh.image().getRGB(3072, 1024) == 0xffa864dc, "changed HTML is re-rendered");
            System.out.println("htmlBrowserLoadMs=" + loadMs + " texture=" + texture.toAbsolutePath());
        } finally {
            restore(HtmlPanoramaRenderer.ENABLE_PROPERTY, previousEnabled);
            restore("skyboxblock.html.width", previousWidth);
            restore("skyboxblock.html.waitMs", previousWait);
        }
    }

    private static Path createGame(Path game, String picture) throws IOException {
        Path studio = game.resolve("config/skyboxblock/skyboxes");
        Files.createDirectories(studio);
        Files.writeString(studio.resolve("01_html.conf"),
                "enable = true\nsky = { id = \"skyboxblock:html_test\" picture = \"" + picture + "\" }\n",
                StandardCharsets.UTF_8);
        return game;
    }

    private static void writePage(Path page, String left, String right) throws IOException {
        String html = """
                <!doctype html>
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <link rel="stylesheet" href="palette.css">
                <style>html,body{margin:0;width:100%;height:100%;overflow:hidden}canvas{display:block;width:100vw;height:100vh}</style>
                </head><body><canvas id="sky"></canvas><script>
                const c=document.getElementById('sky');c.width=innerWidth;c.height=innerHeight;
                const g=c.getContext('2d');g.fillStyle=getComputedStyle(document.documentElement).getPropertyValue('--left').trim();g.fillRect(0,0,c.width/2,c.height);
                g.fillStyle=getComputedStyle(document.documentElement).getPropertyValue('--right').trim();g.fillRect(c.width/2,0,c.width/2,c.height);
                </script></body></html>
                """;
        Files.writeString(page, html, StandardCharsets.UTF_8);
        Files.writeString(page.resolveSibling("palette.css"),
                ":root{--left:" + left + ";--right:" + right + "}\n", StandardCharsets.UTF_8);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
