package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Converts an opted-in local HTML page to a cached 2:1 panorama before pack loading. */
public final class HtmlPanoramaRenderer {
    public static final String ENABLE_PROPERTY = "skyboxblock.html.enabled";
    public static final String BROWSER_PROPERTY = "skyboxblock.html.browser";
    public static final String DYNAMIC_ENABLE_PROPERTY = "skyboxblock.html.dynamic.enabled";
    private static final String WIDTH_PROPERTY = "skyboxblock.html.width";
    private static final String DYNAMIC_WIDTH_PROPERTY = "skyboxblock.html.dynamic.width";
    private static final String DYNAMIC_FPS_PROPERTY = "skyboxblock.html.dynamic.fps";
    private static final String WAIT_PROPERTY = "skyboxblock.html.waitMs";
    private static final String TIMEOUT_PROPERTY = "skyboxblock.html.timeoutMs";
    private static final long MAX_HTML_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CACHE_MISSES_PER_LOAD = 8;
    private static final long MAX_RENDER_TIME_PER_LOAD_MS = 90_000;
    private static final int MAX_INPUT_TREE_FILES = 4096;
    private static int cacheMissesThisLoad;
    private static long renderNanosThisLoad;

    public record Rendered(Path file, BufferedImage image, boolean cacheHit) { }

    private HtmlPanoramaRenderer() { }

    public static synchronized void beginLoad() {
        cacheMissesThisLoad = 0;
        renderNanosThisLoad = 0;
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    }

    public static boolean dynamicEnabled() {
        return enabled() && Boolean.parseBoolean(System.getProperty(DYNAMIC_ENABLE_PROPERTY, "false"));
    }

    public static int dynamicWidth() {
        int width = intProperty(DYNAMIC_WIDTH_PROPERTY, 1024, 512, 2048);
        return width - (width & 1);
    }

    public static int dynamicFps() {
        return intProperty(DYNAMIC_FPS_PROPERTY, 10, 1, 20);
    }

    public static String browserExecutable() throws IOException {
        return resolveBrowser();
    }

    public static BufferedImage placeholderImage() {
        BufferedImage image = new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                boolean alternate = (((x >> 5) ^ (y >> 5)) & 1) != 0;
                image.setRGB(x, y, alternate ? 0xffa51a71 : 0xff19141d);
            }
        }
        return image;
    }

    public static boolean isHtml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    public static Rendered render(Path htmlFile, Path cacheDirectory, int maxWidth, int maxHeight)
            throws IOException {
        if (!enabled()) {
            throw new IOException("HTML panorama import is disabled; set -D" + ENABLE_PROPERTY + "=true to enable it");
        }
        if (!isHtml(htmlFile) || !Files.isRegularFile(htmlFile)) {
            throw new IOException("HTML source is missing or has an unsupported extension");
        }
        long sourceSize = Files.size(htmlFile);
        if (sourceSize > MAX_HTML_BYTES) {
            throw new IOException("HTML source exceeds 16 MiB");
        }
        Path source = htmlFile.toAbsolutePath().normalize();
        byte[] sourceBytes = Files.readAllBytes(source);
        String browser = resolveBrowser();
        int width = Math.min(maxWidth, intProperty(WIDTH_PROPERTY, 4096, 512, 4096));
        width = Math.min(width, maxHeight * 2);
        width -= width & 1;
        int height = width / 2;
        if (width < 512 || height < 256) {
            throw new IOException("HTML panorama viewport must be at least 512x256");
        }
        int virtualWaitMs = intProperty(WAIT_PROPERTY, 1000, 0, 10000);
        String fingerprint = fingerprint(source, sourceBytes, browser, width, height, virtualWaitMs);
        String fileKey = sha256(source.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        Files.createDirectories(cacheDirectory);
        Path cachedImage = cacheDirectory.resolve(fileKey + ".png");
        Path cachedKey = cacheDirectory.resolve(fileKey + ".sha256");
        Rendered cached = readCache(cachedImage, cachedKey, fingerprint, width, height);
        if (cached != null) return cached;
        long timeoutMs = reserveRenderBudget(intProperty(TIMEOUT_PROPERTY, 45000, 1000, 120000));
        long renderStarted = System.nanoTime();

        Path work = null;
        try {
            work = Files.createTempDirectory(cacheDirectory, ".html-render-");
            Path profile = work.resolve("profile");
            Path screenshot = work.resolve("snapshot.png");
            List<String> command = new ArrayList<>();
            command.add(browser);
            command.add("--headless");
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
            command.add("--disable-extensions");
            command.add("--disable-sync");
            command.add("--disable-background-mode");
            command.add("--hide-scrollbars");
            command.add("--force-device-scale-factor=1");
            command.add("--window-size=" + width + "," + height);
            command.add("--virtual-time-budget=" + virtualWaitMs);
            command.add("--user-data-dir=" + profile.toAbsolutePath());
            command.add("--screenshot=" + screenshot.toAbsolutePath());
            command.add(source.toUri().toASCIIString());

            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            List<ProcessHandle> descendants = new ArrayList<>();
            BufferedImage image = waitForScreenshot(process, screenshot, width, height, timeoutMs, descendants);
            moveReplace(screenshot, cachedImage);
            Path keyTemp = work.resolve("cache.sha256");
            Files.writeString(keyTemp, fingerprint, StandardCharsets.US_ASCII);
            moveReplace(keyTemp, cachedKey);
            return new Rendered(cachedImage, image, false);
        } finally {
            recordRenderTime(System.nanoTime() - renderStarted);
            if (work != null) deleteTree(work);
        }
    }

    private static Rendered readCache(Path imageFile, Path keyFile, String fingerprint, int width, int height) {
        if (!Files.isRegularFile(imageFile) || !Files.isRegularFile(keyFile)) return null;
        try {
            if (!fingerprint.equals(Files.readString(keyFile, StandardCharsets.US_ASCII))) return null;
            BufferedImage image = ImageIO.read(imageFile.toFile());
            if (image != null && image.getWidth() == width && image.getHeight() == height) {
                return new Rendered(imageFile, image, true);
            }
        } catch (IOException ignored) {
            // A partial cache is rebuilt below.
        }
        return null;
    }

    private static String resolveBrowser() throws IOException {
        String configured = firstNonBlank(System.getProperty(BROWSER_PROPERTY),
                System.getenv("SKYBOXBLOCK_HTML_BROWSER"));
        if (configured != null) {
            String found = findExecutable(unquote(configured));
            if (found == null) throw new IOException("Configured HTML browser was not found: " + configured);
            return found;
        }
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addPath(candidates, System.getenv("ProgramFiles(x86)"), "Microsoft/Edge/Application/msedge.exe");
            addPath(candidates, System.getenv("ProgramFiles"), "Microsoft/Edge/Application/msedge.exe");
            addPath(candidates, System.getenv("LOCALAPPDATA"), "Microsoft/Edge/Application/msedge.exe");
            addPath(candidates, System.getenv("ProgramFiles"), "Google/Chrome/Application/chrome.exe");
            addPath(candidates, System.getenv("ProgramFiles(x86)"), "Google/Chrome/Application/chrome.exe");
            addPath(candidates, System.getenv("LOCALAPPDATA"), "Google/Chrome/Application/chrome.exe");
            candidates.add("msedge.exe");
            candidates.add("chrome.exe");
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("chromium");
        } else {
            candidates.add("chromium");
            candidates.add("chromium-browser");
            candidates.add("google-chrome");
            candidates.add("microsoft-edge");
        }
        for (String candidate : candidates) {
            String found = findExecutable(candidate);
            if (found != null) return found;
        }
        throw new IOException("No Chromium-based browser found; set -D" + BROWSER_PROPERTY + "=<browser executable>");
    }

    private static String findExecutable(String command) {
        Path direct;
        try {
            direct = Path.of(command);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (direct.isAbsolute() || command.contains("/") || command.contains("\\")) {
            return Files.isRegularFile(direct) ? direct.toAbsolutePath().normalize().toString() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String directory : path.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve(command);
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
        }
        return null;
    }

    private static void addPath(List<String> candidates, String root, String suffix) {
        if (root != null && !root.isBlank()) candidates.add(Path.of(root).resolve(suffix).toString());
    }

    private static String fingerprint(Path source, byte[] bytes, String browser, int width, int height, int waitMs)
            throws IOException {
        MessageDigest digest = digest();
        digest.update("html-panorama-v1".getBytes(StandardCharsets.US_ASCII));
        digest.update(source.toString().getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        digest.update(browser.getBytes(StandardCharsets.UTF_8));
        digest.update((width + "x" + height + ":" + waitMs).getBytes(StandardCharsets.US_ASCII));
        Path browserPath = Path.of(browser);
        if (Files.isRegularFile(browserPath)) {
            digest.update(Long.toString(Files.size(browserPath)).getBytes(StandardCharsets.US_ASCII));
            digest.update(Long.toString(Files.getLastModifiedTime(browserPath).toMillis())
                    .getBytes(StandardCharsets.US_ASCII));
        }
        Path inputRoot = source.getParent();
        List<Path> dependencies;
        try (Stream<Path> paths = Files.walk(inputRoot)) {
            dependencies = new ArrayList<>(paths.filter(Files::isRegularFile)
                    .limit(MAX_INPUT_TREE_FILES + 1L).toList());
        }
        if (dependencies.size() > MAX_INPUT_TREE_FILES) {
            throw new IOException("HTML input folder exceeds " + MAX_INPUT_TREE_FILES + " files");
        }
        dependencies.sort(java.util.Comparator.comparing(path -> inputRoot.relativize(path).toString()));
        for (Path dependency : dependencies) {
            digest.update(inputRoot.relativize(dependency).toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(Files.size(dependency)).getBytes(StandardCharsets.US_ASCII));
            digest.update(Long.toString(Files.getLastModifiedTime(dependency).toMillis())
                    .getBytes(StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static synchronized long reserveRenderBudget(long requestedTimeoutMs) throws IOException {
        if (cacheMissesThisLoad >= MAX_CACHE_MISSES_PER_LOAD) {
            throw new IOException("HTML cache-miss limit reached for this startup (" + MAX_CACHE_MISSES_PER_LOAD + ")");
        }
        long remainingMs = MAX_RENDER_TIME_PER_LOAD_MS - TimeUnit.NANOSECONDS.toMillis(renderNanosThisLoad);
        if (remainingMs <= 0) throw new IOException("HTML render time budget exhausted for this startup");
        cacheMissesThisLoad++;
        return Math.min(requestedTimeoutMs, remainingMs);
    }

    private static synchronized void recordRenderTime(long elapsedNanos) {
        renderNanosThisLoad += Math.max(0, elapsedNanos);
    }

    private static BufferedImage waitForScreenshot(Process process, Path screenshot, int width, int height,
                                                    long timeoutMs, List<ProcessHandle> descendants)
            throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long lastSize = -1;
        int stableSamples = 0;
        try {
            while (true) {
                observeDescendants(process, descendants);
                if (Files.isRegularFile(screenshot)) {
                    long size;
                    try {
                        size = Files.size(screenshot);
                    } catch (IOException incomplete) {
                        size = -1;
                    }
                    if (size > 8 && size == lastSize) stableSamples++;
                    else stableSamples = 0;
                    lastSize = size;
                    if (stableSamples >= 2) {
                        BufferedImage image;
                        try {
                            image = readScreenshot(screenshot, width, height, false);
                        } catch (IOException invalidImage) {
                            terminate(process, descendants);
                            throw invalidImage;
                        }
                        if (image != null) {
                            terminate(process, descendants);
                            return image;
                        }
                        stableSamples = 0;
                    }
                }
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    try {
                        BufferedImage image = readScreenshot(screenshot, width, height, true);
                        terminate(process, descendants);
                        return image;
                    } catch (IOException invalidImage) {
                        terminate(process, descendants);
                        throw new IOException("HTML browser exited with code " + exitCode
                                + " without a valid panorama PNG", invalidImage);
                    }
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    terminate(process, descendants);
                    throw new IOException("HTML browser rendering exceeded " + timeoutMs + " ms");
                }
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            terminate(process, descendants);
            Thread.currentThread().interrupt();
            throw new IOException("HTML browser rendering was interrupted", interrupted);
        }
    }

    private static BufferedImage readScreenshot(Path screenshot, int width, int height, boolean strict)
            throws IOException {
        if (!Files.isRegularFile(screenshot)) {
            if (strict) throw new IOException("HTML browser exited without producing a panorama PNG");
            return null;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(screenshot.toFile());
        } catch (IOException incomplete) {
            if (strict) throw incomplete;
            return null;
        }
        if (image == null) {
            if (strict) throw new IOException("HTML browser produced an unreadable panorama PNG");
            return null;
        }
        if (image.getWidth() != width || image.getHeight() != height) {
            throw new IOException("HTML browser produced " + image.getWidth() + "x" + image.getHeight()
                    + "; expected " + width + "x" + height);
        }
        return image;
    }

    private static void observeDescendants(Process process, List<ProcessHandle> observed) {
        for (ProcessHandle child : process.toHandle().descendants().toList()) {
            if (!observed.contains(child)) observed.add(child);
        }
    }

    private static void terminate(Process process, List<ProcessHandle> observed) {
        observeDescendants(process, observed);
        for (int index = observed.size() - 1; index >= 0; index--) observed.get(index).destroyForcibly();
        process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        waitForDescendants(observed, deadline);
        try {
            process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitForDescendants(List<ProcessHandle> descendants, long deadline) {
        for (ProcessHandle child : descendants) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                child.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // Keep cleanup bounded even when a browser child does not exit promptly.
            }
        }
    }

    private static int intProperty(String property, int fallback, int min, int max) {
        String value = System.getProperty(property);
        if (value == null) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void moveReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary browser data is best-effort cleanup after process exit.
                }
            });
        } catch (IOException ignored) {
            // Do not hide the rendering result with a temporary cleanup error.
        }
    }
}
