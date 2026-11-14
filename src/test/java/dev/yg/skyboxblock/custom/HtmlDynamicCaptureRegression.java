package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Opt-in live Chromium test for animated Canvas frames and dimension/FPS bounds. */
public final class HtmlDynamicCaptureRegression {
    private static int checks;

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) throws Exception {
        String browser = System.getProperty(HtmlPanoramaRenderer.BROWSER_PROPERTY);
        if (browser == null || browser.isBlank()) {
            throw new IllegalArgumentException("Set -D" + HtmlPanoramaRenderer.BROWSER_PROPERTY + " to Chromium/Edge");
        }
        Path root = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("skybox-html-live-test-");
        Files.createDirectories(root);
        Path page = root.resolve("animated.html");
        Files.writeString(page, """
                <!doctype html><html><head><meta charset="utf-8">
                <style>html,body{margin:0;width:100%;height:100%;overflow:hidden}canvas{width:100vw;height:100vh;display:block}</style>
                </head><body><canvas id="sky"></canvas><script>
                const canvas=document.getElementById('sky');canvas.width=innerWidth;canvas.height=innerHeight;
                const context=canvas.getContext('2d');let frame=0;
                function draw(){context.fillStyle=`rgb(${frame%256},${frame*3%256},${frame*7%256})`;
                context.fillRect(0,0,canvas.width,canvas.height);frame++;requestAnimationFrame(draw)}draw();
                </script></body></html>
                """, StandardCharsets.UTF_8);

        Set<Integer> colors = new HashSet<>();
        int observedFrames = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        try (HtmlDynamicCapture capture = new HtmlDynamicCapture(page, root.resolve("cache"), browser, 512, 5)) {
            capture.start();
            while (System.nanoTime() < deadline && colors.size() < 3) {
                String failure = capture.failure();
                if (failure != null) throw new AssertionError("Live capture failed: " + failure);
                byte[] frame = capture.takeLatestFrame();
                if (frame != null) {
                    BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(frame));
                    check(image != null, "screencast frame decodes as PNG");
                    check(image.getWidth() == 512 && image.getHeight() == 256, "screencast dimensions are fixed to 2:1");
                    int color = image.getRGB(256, 128);
                    observedFrames++;
                    colors.add(color);
                }
                Thread.sleep(40);
            }
            System.out.println("dynamicFrames=" + observedFrames + " colors=" + colors
                    + " capture=" + capture.diagnostics() + " failure=" + capture.failure());
            check(colors.size() >= 3, "live animated page yields multiple distinct frames");
            check(capture.running(), "Chromium session remains alive after streaming frames");
        }
        System.out.println("PASS: " + checks + " dynamic HTML assertions; distinctFrames=" + colors.size());
    }
}
