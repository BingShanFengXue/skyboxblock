package dev.yg.skyboxblock.custom;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Persistent headless Chromium session that exposes throttled DevTools screencast frames. */
public final class HtmlDynamicCapture implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final Path source;
    private final Path workDir;
    private final Path profileDir;
    private final String browser;
    private final int width;
    private final int height;
    private final int fps;
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();
    private final AtomicInteger commandIds = new AtomicInteger();
    private final ConcurrentMap<Integer, String> commandMethods = new ConcurrentHashMap<>();
    private final ScheduledExecutorService captureScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "skyboxblock-html-capture");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger messagesReceived = new AtomicInteger();
    private final AtomicInteger framesReceived = new AtomicInteger();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private volatile boolean socketOpened;
    private volatile String lastEvent = "none";
    private volatile boolean canvasFallback;
    private final StringBuilder messageBuffer = new StringBuilder();
    private volatile boolean closed;
    private volatile Process process;
    private volatile WebSocket socket;
    private volatile Thread starter;
    private volatile Path activePortFile;

    public HtmlDynamicCapture(Path htmlFile, Path cacheRoot, String browser, int width, int fps)
            throws IOException {
        if (!isHtml(htmlFile) || !Files.isRegularFile(htmlFile)) {
            throw new IOException("HTML source is missing or has an unsupported extension: " + htmlFile);
        }
        this.source = htmlFile.toAbsolutePath().normalize();
        this.browser = browser;
        this.width = Math.max(512, Math.min(2048, width)) & ~1;
        this.height = this.width / 2;
        this.fps = Math.max(1, Math.min(20, fps));
        Files.createDirectories(cacheRoot);
        this.workDir = Files.createTempDirectory(cacheRoot, ".html-live-");
        this.profileDir = workDir.resolve("profile");
    }

    public static boolean isHtml(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm");
    }

    public synchronized void start() {
        if (starter != null || closed) return;
        starter = new Thread(this::launch, "skyboxblock-html-start");
        starter.setDaemon(true);
        starter.start();
    }

    public byte[] takeLatestFrame() {
        return latestFrame.getAndSet(null);
    }

    public String failure() {
        return failure.get();
    }

    public boolean running() {
        Process current = process;
        return !closed && current != null && current.isAlive() && failure.get() == null;
    }

    public String diagnostics() {
        return "socketOpened=" + socketOpened + ", messages=" + messagesReceived.get()
                + ", frames=" + framesReceived.get() + ", lastEvent=" + lastEvent;
    }

    private void launch() {
        try {
            Files.createDirectories(profileDir);
            List<String> command = List.of(browser,
                    "--headless",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-extensions",
                    "--disable-sync",
                    "--disable-background-mode",
                    "--disable-background-timer-throttling",
                    "--disable-renderer-backgrounding",
                    "--disable-backgrounding-occluded-windows",
                    "--hide-scrollbars",
                    "--force-device-scale-factor=1",
                    "--window-size=" + width + "," + height,
                    "--remote-debugging-address=127.0.0.1",
                    "--remote-debugging-port=0",
                    "--remote-allow-origins=*",
                    "--user-data-dir=" + profileDir.toAbsolutePath(),
                    source.toUri().toASCIIString());
            Process launched = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process = launched;
            if (closed) {
                terminateTree(launched);
                cleanup();
                return;
            }
            launched.onExit().thenAccept(ignored -> {
                if (!closed && failure.get() == null) {
                    failure.compareAndSet(null, "Chromium exited before the live session stopped");
                }
            });

            int port = awaitDebugPort(launched);
            URI pageSocket = awaitPageSocket(launched, port);
            WebSocket connected = HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(pageSocket, new DevToolsListener())
                    .get(10, TimeUnit.SECONDS);
            socket = connected;
            if (closed) {
                connected.sendClose(WebSocket.NORMAL_CLOSURE, "session stopped");
                terminateTree(launched);
                cleanup();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!closed) fail("Chromium startup was interrupted");
            stopProcess();
        } catch (Exception error) {
            if (!closed) fail(error.getClass().getSimpleName() + ": " + error.getMessage());
            stopProcess();
        }
    }

    private int awaitDebugPort(Process launched) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        activePortFile = profileDir.resolve("DevToolsActivePort");
        while (!closed && launched.isAlive() && System.nanoTime() < deadline) {
            if (Files.isRegularFile(activePortFile)) {
                List<String> lines = Files.readAllLines(activePortFile, StandardCharsets.US_ASCII);
                if (!lines.isEmpty()) {
                    try {
                        int port = Integer.parseInt(lines.get(0).trim());
                        if (port > 0 && port <= 65535) return port;
                    } catch (NumberFormatException ignored) {
                        // Chromium may still be writing the port file.
                    }
                }
            }
            Thread.sleep(50);
        }
        if (closed) throw new IOException("Dynamic HTML session stopped during Chromium startup");
        throw new IOException("Chromium DevTools port was not ready within 20 seconds");
    }

    private URI awaitPageSocket(Process launched, int port) throws Exception {
        URI listUri = URI.create("http://127.0.0.1:" + port + "/json/list");
        HttpRequest request = HttpRequest.newBuilder(listUri).timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!closed && launched.isAlive() && System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonArray targets = JsonParser.parseString(response.body()).getAsJsonArray();
                    for (int i = 0; i < targets.size(); i++) {
                        JsonObject target = targets.get(i).getAsJsonObject();
                        if (target.has("type") && "page".equals(target.get("type").getAsString())
                                && target.has("webSocketDebuggerUrl")) {
                            return URI.create(target.get("webSocketDebuggerUrl").getAsString());
                        }
                    }
                }
            } catch (IOException ignored) {
                // The remote debugging endpoint can lag the port-file creation briefly.
            }
            Thread.sleep(100);
        }
        if (closed) throw new IOException("Dynamic HTML session stopped before the page target appeared");
        throw new IOException("Chromium page target did not appear within 15 seconds");
    }

    private final class DevToolsListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            HtmlDynamicCapture.this.socket = webSocket;
            socketOpened = true;
            webSocket.request(Long.MAX_VALUE);
            sendCommand("Page.enable", new JsonObject());
            JsonObject metrics = new JsonObject();
            metrics.addProperty("width", width);
            metrics.addProperty("height", height);
            metrics.addProperty("deviceScaleFactor", 1);
            metrics.addProperty("mobile", false);
            sendCommand("Emulation.setDeviceMetricsOverride", metrics);
            sendCommand("Runtime.enable", new JsonObject());
            JsonObject lifecycle = new JsonObject();
            lifecycle.addProperty("enabled", true);
            sendCommand("Page.setLifecycleEventsEnabled", lifecycle);
            sendCommand("Page.bringToFront", new JsonObject());
            JsonObject reload = new JsonObject();
            reload.addProperty("ignoreCache", false);
            sendCommand("Page.reload", reload);
            long intervalMs = Math.max(50, 1000L / fps);
            captureScheduler.scheduleAtFixedRate(HtmlDynamicCapture.this::requestScreenshot, 3000, intervalMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messagesReceived.incrementAndGet();
            synchronized (messageBuffer) {
                messageBuffer.append(data);
                if (last) {
                    String message = messageBuffer.toString();
                    messageBuffer.setLength(0);
                    handleMessage(webSocket, message);
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed) fail("Chromium DevTools socket closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed) fail("Chromium DevTools socket failed: " + error);
        }
    }

    private void handleMessage(WebSocket webSocket, String message) {
        try {
            JsonObject root = JsonParser.parseString(message).getAsJsonObject();
            if (root.has("id")) {
                int id = root.get("id").getAsInt();
                String command = commandMethods.remove(id);
                if (root.has("error")) {
                    lastEvent = "command-error:" + (command == null ? "unknown" : command) + ":" + root.get("error");
                    if (!"Runtime.evaluate".equals(command)) {
                        canvasFallback = true;
                        captureScheduler.execute(this::requestCanvasFrame);
                    } else {
                        fail("Chromium Canvas fallback failed: " + root.get("error"));
                    }
                    return;
                }
                lastEvent = command == null ? "command-response" : command;
                if ("Page.captureScreenshot".equals(command) && root.has("result")) {
                    JsonObject result = root.getAsJsonObject("result");
                    if (result.has("data")) acceptFrameData(result.get("data").getAsString());
                } else if ("Runtime.evaluate".equals(command) && root.has("result")) {
                    JsonObject result = root.getAsJsonObject("result");
                    JsonObject value = result == null ? null : result.getAsJsonObject("result");
                    if (value != null && value.has("value") && !value.get("value").isJsonNull()) {
                        acceptFrameData(value.get("value").getAsString());
                    } else {
                        fail("HTML page has no canvas and captureScreenshot is unavailable");
                    }
                }
                return;
            }
            if (!root.has("method")) {
                lastEvent = "message-without-method";
                return;
            }
            String method = root.get("method").getAsString();
            lastEvent = method;
            if (!"Page.screencastFrame".equals(method)) return;
            JsonObject params = root.getAsJsonObject("params");
            if (params == null || !params.has("data") || !params.has("sessionId")) return;
            String encoded = params.get("data").getAsString();
            if (encoded.length() > (long) MAX_FRAME_BYTES * 4 / 3 + 16) {
                throw new IllegalArgumentException("frame exceeds " + MAX_FRAME_BYTES + " bytes");
            }
            byte[] frame = Base64.getDecoder().decode(encoded);
            if (frame.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("frame is too large");
            framesReceived.incrementAndGet();
            latestFrame.set(frame);
            JsonObject ack = new JsonObject();
            ack.addProperty("sessionId", params.get("sessionId").getAsInt());
            sendCommand("Page.screencastFrameAck", ack);
        } catch (RuntimeException error) {
            fail("Invalid DevTools screencast frame: " + error.getMessage());
            webSocket.sendClose(1002, "invalid screencast frame");
        }
    }

    private void acceptFrameData(String raw) {
        String encoded = raw;
        int comma = encoded.indexOf(',');
        if (encoded.startsWith("data:") && comma >= 0) encoded = encoded.substring(comma + 1);
        if (encoded.length() > (long) MAX_FRAME_BYTES * 4 / 3 + 16) {
            throw new IllegalArgumentException("frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        byte[] frame = Base64.getDecoder().decode(encoded);
        if (frame.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("frame is too large");
        framesReceived.incrementAndGet();
        latestFrame.set(frame);
    }

    private void requestScreenshot() {
        if (closed || socket == null || failure.get() != null) return;
        requestCanvasFrame();
    }

    private void requestCanvasFrame() {
        JsonObject params = new JsonObject();
        params.addProperty("expression", "(() => { const fn = window.skyboxblockCapture; const c = document.querySelector('canvas'); const v = typeof fn === 'function' ? fn() : (c ? c.toDataURL('image/png') : null); return v; })()");
        params.addProperty("returnByValue", true);
        params.addProperty("awaitPromise", true);
        sendCommand("Runtime.evaluate", params);
    }

    private int sendCommand(String method, JsonObject params) {
        WebSocket current = socket;
        if (current == null || closed) return -1;
        int id = commandIds.incrementAndGet();
        commandMethods.put(id, method);
        JsonObject command = new JsonObject();
        command.addProperty("id", id);
        command.addProperty("method", method);
        command.add("params", params);
        current.sendText(command.toString(), true).whenComplete((ignored, error) -> {
            if (error != null) {
                commandMethods.remove(id);
                if (!closed) fail("Could not send Chromium DevTools command: " + error);
            }
        });
        return id;
    }

    private void fail(String reason) {
        failure.compareAndSet(null, reason);
    }

    private void stopProcess() {
        Process current = process;
        if (current != null) terminateTree(current);
        cleanup();
    }

    private static void terminateTree(Process current) {
        List<ProcessHandle> descendants = current.toHandle().descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroyForcibly();
        current.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (ProcessHandle child : descendants) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                child.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // Keep shutdown bounded if a browser child is slow to exit.
            }
        }
        try {
            current.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanup() {
        if (!Files.exists(workDir)) return;
        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Cleanup does not change the capture outcome.
                }
            });
        } catch (IOException ignored) {
            // A leftover temporary profile can be removed on the next launch.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        captureScheduler.shutdownNow();
        latestFrame.set(null);
        WebSocket current = socket;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "skyboxblock stopped");
        Thread launchThread = starter;
        if (launchThread != null && launchThread != Thread.currentThread()) {
            launchThread.interrupt();
            try {
                launchThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        stopProcess();
    }
}
