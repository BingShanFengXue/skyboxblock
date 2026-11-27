package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.custom.CustomSkyRegistry;
import dev.yg.skyboxblock.custom.HtmlDynamicCapture;
import dev.yg.skyboxblock.custom.HtmlPanoramaRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Owns opt-in Chromium sessions and uploads only the newest live panorama frame. */
public final class HtmlDynamicSkyManager {
    private static final int MAX_LIVE_PAGES = 4;
    private static final List<LivePage> PAGES = new ArrayList<>();
    private static boolean registered;
    private static boolean initialized;
    private static boolean stopping;

    private HtmlDynamicSkyManager() { }

    public static synchronized void register() {
        if (registered || !HtmlPanoramaRenderer.dynamicEnabled()) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(HtmlDynamicSkyManager::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(HtmlDynamicSkyManager::stop);
    }

    private static void tick(Minecraft client) {
        if (!HtmlPanoramaRenderer.dynamicEnabled()) {
            if (!PAGES.isEmpty()) {
                for (LivePage page : PAGES) {
                    page.capture.close();
                    restoreStatic(client, page);
                }
                PAGES.clear();
                initialized = false;
            }
            return;
        }
        if (!initialized) start(client);
        if (stopping) return;
        long now = System.nanoTime();
        long minimumFrameNanos = 1_000_000_000L / HtmlPanoramaRenderer.dynamicFps();
        for (LivePage page : PAGES) {
            String failure = page.capture.failure();
            if (failure != null) {
                failPage(client, page, failure);
                continue;
            }
            if (now - page.lastUploadNanos < minimumFrameNanos) continue;
            byte[] png = page.capture.takeLatestFrame();
            if (png == null) continue;
            String invalidFrame = invalidLiveFrameReason(png, page.width, page.height);
            if (invalidFrame != null) {
                failPage(client, page, invalidFrame);
                continue;
            }
            try {
                NativeImage image = NativeImage.read(png);
                if (image.getWidth() != page.width || image.getHeight() != page.height) {
                    image.close();
                    throw new IOException("Chromium frame dimensions changed");
                }
                featherWrap(image);
                AbstractTexture current = client.getTextureManager().getTexture(page.textureId);
                if (current == page.texture && page.texture != null) {
                    page.texture.setPixels(image);
                    page.texture.upload();
                } else {
                    DynamicTexture dynamicTexture = new DynamicTexture(
                            () -> "skyboxblock live HTML " + page.styleId, image);
                    dynamicTexture.upload();
                    client.getTextureManager().register(page.textureId, dynamicTexture);
                    page.texture = dynamicTexture;
                }
                page.lastUploadNanos = now;
                if (!page.loggedLive) {
                    page.loggedLive = true;
                    SkyboxBlockMod.LOGGER.info("[skyboxblock] live HTML sky {} active at {}x{} / {} fps",
                            page.styleId, page.width, page.height, HtmlPanoramaRenderer.dynamicFps());
                }
            } catch (IOException | RuntimeException error) {
                failPage(client, page, error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
    }

    /** Null accepts the frame; a reason routes it through failPage and restores the static texture. */
    public static String invalidLiveFrameReason(byte[] png, int width, int height) {
        try {
            HtmlDynamicCapture.validatePngFrame(png, width, height);
            return null;
        } catch (IOException | RuntimeException invalid) {
            return invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
        }
    }

    private static void start(Minecraft client) {
        initialized = true;
        if (CustomSkyRegistry.baseDir() == null || CustomSkyRegistry.generatedDir() == null) return;
        String browser;
        try {
            browser = HtmlPanoramaRenderer.browserExecutable();
        } catch (IOException error) {
            SkyboxBlockMod.LOGGER.warn("[skyboxblock] live HTML capture unavailable: {}", error.getMessage());
            return;
        }
        int width = HtmlPanoramaRenderer.dynamicWidth();
        int fps = HtmlPanoramaRenderer.dynamicFps();
        Path cache = CustomSkyRegistry.generatedDir().resolve("html-cache").resolve("live");
        for (CustomSkyRegistry.Style style : CustomSkyRegistry.styles()) {
            Path source = CustomSkyRegistry.baseDir().resolve(style.picture());
            if (!isHtml(source) || !Files.isRegularFile(source)) continue;
            if (PAGES.size() >= MAX_LIVE_PAGES) {
                SkyboxBlockMod.LOGGER.warn("[skyboxblock] live HTML page limit is {}; remaining styles stay static",
                        MAX_LIVE_PAGES);
                break;
            }
            try {
                HtmlDynamicCapture capture = new HtmlDynamicCapture(source, cache, browser, width, fps);
                PAGES.add(new LivePage(style.id(), style.panorama(), width, width / 2, capture));
                capture.start();
            } catch (IOException | RuntimeException error) {
                SkyboxBlockMod.LOGGER.warn("[skyboxblock] could not start live HTML sky {}: {}",
                        style.id(), error.toString());
            }
        }
        if (!PAGES.isEmpty()) {
            SkyboxBlockMod.LOGGER.info("[skyboxblock] starting {} live HTML sky page(s), target {}x{} at {} fps",
                    PAGES.size(), width, width / 2, fps);
        }
    }

    private static boolean isHtml(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    private static void failPage(Minecraft client, LivePage page, String reason) {
        if (page.failed) return;
        page.failed = true;
        page.capture.close();
        restoreStatic(client, page);
        SkyboxBlockMod.LOGGER.warn("[skyboxblock] live HTML sky {} stopped; static snapshot restored: {}",
                page.styleId, reason);
    }

    private static void restoreStatic(Minecraft client, LivePage page) {
        Path staticFile = CustomSkyRegistry.generatedDir().resolve("assets")
                .resolve(page.textureId.getNamespace()).resolve(page.textureId.getPath());
        try (var stream = Files.newInputStream(staticFile)) {
            NativeImage image = NativeImage.read(stream);
            DynamicTexture fallback = new DynamicTexture(
                    () -> "skyboxblock static HTML fallback " + page.styleId, image);
            fallback.upload();
            client.getTextureManager().register(page.textureId, fallback);
            page.texture = fallback;
            page.lastUploadNanos = System.nanoTime();
        } catch (IOException | RuntimeException error) {
            client.getTextureManager().release(page.textureId);
            page.texture = null;
            SkyboxBlockMod.LOGGER.warn("[skyboxblock] static HTML fallback could not be restored for {}: {}",
                    page.styleId, error.toString());
        }
    }

    private static void stop(Minecraft client) {
        stopping = true;
        for (LivePage page : PAGES) {
            page.capture.close();
            if (page.texture != null) client.getTextureManager().release(page.textureId);
        }
        PAGES.clear();
    }

    private static void featherWrap(NativeImage image) {
        int width = image.getWidth();
        int band = Math.max(1, Math.min(8, width / 64));
        for (int y = 0; y < image.getHeight(); y++) {
            for (int i = 0; i < band; i++) {
                double t = (i + 1.0) / (band + 1.0);
                int left = image.getPixel(i, y);
                int right = image.getPixel(width - 1 - i, y);
                image.setPixel(i, y, blend(left, right, t));
                image.setPixel(width - 1 - i, y, blend(right, left, t));
            }
        }
    }

    private static int blend(int a, int b, double t) {
        int r = (int) Math.round(((a >>> 16) & 0xff) * (1 - t) + ((b >>> 16) & 0xff) * t);
        int g = (int) Math.round(((a >>> 8) & 0xff) * (1 - t) + ((b >>> 8) & 0xff) * t);
        int bl = (int) Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return 0xff000000 | (r << 16) | (g << 8) | bl;
    }

    private static final class LivePage {
        private final String styleId;
        private final Identifier textureId;
        private final int width;
        private final int height;
        private final HtmlDynamicCapture capture;
        private DynamicTexture texture;
        private long lastUploadNanos;
        private boolean loggedLive;
        private boolean failed;

        private LivePage(String styleId, Identifier textureId, int width, int height, HtmlDynamicCapture capture) {
            this.styleId = styleId;
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.capture = capture;
        }
    }
}
