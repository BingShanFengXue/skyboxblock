package dev.yg.skyboxblock.custom;

import com.google.gson.JsonObject;
import dev.yg.skyboxblock.client.HtmlDynamicSkyManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in live Chromium test for animated Canvas frames and dimension/FPS bounds. */
public final class HtmlDynamicCaptureRegression {
    private static int checks;

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private static final class OversizedText implements CharSequence {
        private final int length;
        OversizedText(int length) { this.length = length; }
        @Override public int length() { return length; }
        @Override public char charAt(int index) { throw new AssertionError("oversized fragment was copied"); }
        @Override public CharSequence subSequence(int start, int end) { throw new AssertionError("fragment was sliced"); }
    }

    private static WebSocket fakeSocket(AtomicBoolean aborted) {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "abort" -> { aborted.set(true); yield null; }
                    case "request" -> proxy;
                    case "sendText", "sendClose" -> CompletableFuture.completedFuture(proxy);
                    case "isInputClosed", "isOutputClosed" -> false;
                    case "toString" -> "audit-websocket";
                    default -> null;
                });
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x, y, 0xff000000 | (x & 0xff) << 16 | (y & 0xff) << 8 | ((x + y) & 0xff));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        check(ImageIO.write(image, "PNG", output), "PNG encoder available for normal-frame fixture");
        return output.toByteArray();
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] png = new byte[33];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        System.arraycopy(signature, 0, png, 0, signature.length);
        writeU32(png, 8, 13); png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        writeU32(png, 16, width); writeU32(png, 20, height);
        png[24] = 8; png[25] = 6;
        return png;
    }

    private static void writeU32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24); target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8); target[offset + 3] = (byte) value;
    }

    private static void runBudgetRegressions(Path root) throws Exception {
        Files.createDirectories(root);
        Path page = root.resolve("budget-fixture.html");
        Files.writeString(page, "<!doctype html><title>budget fixture</title>", StandardCharsets.UTF_8);

        AtomicBoolean overflowAborted = new AtomicBoolean();
        try (HtmlDynamicCapture capture = new HtmlDynamicCapture(page, root.resolve("overflow-cache"), "unused", 512, 5)) {
            HtmlDynamicCapture.DevToolsListener listener = capture.new DevToolsListener();
            WebSocket socket = fakeSocket(overflowAborted);
            listener.onText(socket, "x".repeat(64), false);
            listener.onText(socket, new OversizedText(HtmlDynamicCapture.MAX_MESSAGE_CHARS - 32), true);
            check(overflowAborted.get(), "aggregated oversized fragmented CDP message aborts socket");
            check(capture.failure() != null && capture.failure().contains("exceeds"), "aggregate overflow records capture failure");
            check(capture.takeLatestFrame() == null, "aggregate overflow discards queued frame before decoding");
        }

        AtomicBoolean singleAborted = new AtomicBoolean();
        try (HtmlDynamicCapture capture = new HtmlDynamicCapture(page, root.resolve("single-cache"), "unused", 512, 5)) {
            HtmlDynamicCapture.DevToolsListener listener = capture.new DevToolsListener();
            listener.onText(fakeSocket(singleAborted), new OversizedText(HtmlDynamicCapture.MAX_MESSAGE_CHARS + 1), true);
            check(singleAborted.get(), "single oversized text fragment aborts socket");
            check(capture.failure() != null && capture.takeLatestFrame() == null, "single oversized message is rejected before dispatch");
        }

        byte[] normal = png(512, 256);
        HtmlDynamicCapture.validatePngFrame(normal, 512, 256);
        check(HtmlDynamicSkyManager.invalidLiveFrameReason(normal, 512, 256) == null,
                "normal target-sized PNG passes the pre-decode validator");
        try (HtmlDynamicCapture capture = new HtmlDynamicCapture(page, root.resolve("normal-cache"), "unused", 512, 5)) {
            JsonObject params = new JsonObject(); params.addProperty("data", Base64.getEncoder().encodeToString(normal));
            params.addProperty("sessionId", 1);
            JsonObject event = new JsonObject(); event.addProperty("method", "Page.screencastFrame"); event.add("params", params);
            capture.new DevToolsListener().onText(fakeSocket(new AtomicBoolean()), event.toString(), true);
            byte[] accepted = capture.takeLatestFrame();
            check(java.util.Arrays.equals(normal, accepted), "normal screencast frame reaches the upload queue");
        }

        byte[] oversized = pngHeader(4097, 2048);
        String fallbackReason = HtmlDynamicSkyManager.invalidLiveFrameReason(oversized, 512, 256);
        check(fallbackReason != null && fallbackReason.contains("dimensions"),
                "oversized frame selects the manager's static-fallback path before NativeImage allocation");
        AtomicBoolean frameAborted = new AtomicBoolean();
        try (HtmlDynamicCapture capture = new HtmlDynamicCapture(page, root.resolve("oversize-cache"), "unused", 512, 5)) {
            JsonObject params = new JsonObject(); params.addProperty("data", Base64.getEncoder().encodeToString(oversized));
            params.addProperty("sessionId", 1);
            JsonObject event = new JsonObject(); event.addProperty("method", "Page.screencastFrame"); event.add("params", params);
            capture.new DevToolsListener().onText(fakeSocket(frameAborted), event.toString(), true);
            check(frameAborted.get() && capture.failure() != null, "oversized IHDR is rejected at capture boundary");
            check(capture.takeLatestFrame() == null, "oversized PNG never reaches NativeImage consumer");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("skybox-html-live-test-");
        Files.createDirectories(root);
        runBudgetRegressions(root.resolve("budget-regression"));
        String browser = System.getProperty(HtmlPanoramaRenderer.BROWSER_PROPERTY);
        if (browser == null || browser.isBlank()) {
            System.out.println("PASS: " + checks + " dynamic HTML budget assertions; Chromium smoke test skipped");
            return;
        }
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
                    observedFrames++;
                    colors.add(image.getRGB(256, 128));
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
