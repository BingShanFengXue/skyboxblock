package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import dev.yg.skyboxblock.client.SkyboxServerState;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import dev.yg.skyboxblock.network.SkyboxConfigPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** Opt-in control plane for a fresh owned dedicated-server/two-client fixture, never a user instance. */
public final class R36MultiplayerProbe {
    private static final Logger LOG = LoggerFactory.getLogger("skybox-r36-multiplayer");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_CONTROL_BYTES = 32768;
    private static boolean initialized, active;
    private static Path gameDir, evidence;
    private static String ownerToken, playerName;
    private static int port, ticks;
    private static long lastSequence, messageCounter;
    private static JsonObject lastResult;
    private static final ArrayDeque<JsonObject> messages = new ArrayDeque<>();
    private static CompletableFuture<Suggestions> suggestionFuture;
    private static JsonObject pending;
    private static long pendingDeadline;

    private R36MultiplayerProbe() { }

    /** Parent probe entrypoint calls this first; disabled unless BOTH the flag and owned marker match. */
    public static void initialize() {
        if (initialized || !Boolean.getBoolean("skyboxblock.r36.multiplayerProbe")) return;
        initialized = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            gameDir = mc.gameDirectory.toPath().toRealPath();
            if (gameDir.getParent() == null || gameDir.getParent().getParent() == null
                    || !gameDir.getParent().getParent().getFileName().toString().equals("r36-multiplayer")
                    || !gameDir.getParent().getParent().getParent().getFileName().toString().equals("build"))
                throw new IOException("Not an owned build/r36-multiplayer/<label>/<client> directory");
            JsonObject marker = readObject(gameDir.resolve("r36-owned.json"), 16384);
            ownerToken = marker.get("ownerToken").getAsString();
            playerName = marker.get("playerName").getAsString();
            port = marker.get("serverPort").getAsInt();
            if (!marker.get("owned").getAsBoolean() || marker.get("schemaVersion").getAsInt() != 1
                    || !marker.get("kind").getAsString().equals("r36-multiplayer-client")
                    || !marker.get("host").getAsString().equals("127.0.0.1")
                    || !Path.of(marker.get("gameDir").getAsString()).toAbsolutePath().normalize().equals(gameDir)
                    || !ownerToken.matches("[a-f0-9]{32}")
                    || !ownerToken.equals(System.getProperty("skyboxblock.r36.multiplayerProbe.owner", ""))
                    || !(playerName.equals("SkyboxA") || playerName.equals("SkyboxB")) || port < 1024 || port > 65535)
                throw new IOException("Owned marker/JVM identity mismatch");
            if (mc.getSingleplayerServer() != null) throw new IOException("Integrated worlds are not multiplayer evidence");
            evidence = gameDir.resolve("r36-evidence");
            if (Files.isSymbolicLink(evidence)) throw new IOException("Evidence directory is a symbolic link");
            Files.createDirectories(evidence);
            Path previous = gameDir.resolve("r36-client-state.json");
            if (Files.exists(previous)) {
                JsonObject state = readObject(previous, 262144);
                if (ownerToken.equals(state.get("ownerToken").getAsString())) lastSequence = state.get("sequence").getAsLong();
            }
            active = true;
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
                if (!active || !ownedConnection(Minecraft.getInstance())) return;
                JsonObject item = new JsonObject();
                item.addProperty("id", ++messageCounter);
                item.addProperty("text", message.getString().substring(0, Math.min(1024, message.getString().length())));
                item.addProperty("overlay", overlay);
                item.addProperty("nanoTime", System.nanoTime());
                messages.addLast(item);
                while (messages.size() > 128) messages.removeFirst();
            });
            ClientTickEvents.END_CLIENT_TICK.register(R36MultiplayerProbe::tick);
            lastResult = result(lastSequence, "initialize", "READY");
            writeState(mc, lastResult);
            LOG.info("[r36-multiplayer] owned helper ready player={} gameDir={}", playerName, gameDir);
        } catch (Exception error) {
            throw new IllegalStateException("R36 multiplayer owned-fixture guard rejected", error);
        }
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        try {
            ticks++;
            if (mc.getSingleplayerServer() != null) throw new IOException("Unexpected integrated server");
            if (mc.getConnection() != null && !ownedConnection(mc)) throw new IOException("Unexpected non-owned remote connection");
            mc.options.pauseOnLostFocus = false;
            net.minecraft.client.KeyMapping.releaseAll();
            if (suggestionFuture != null) {
                if (suggestionFuture.isDone()) {
                    JsonObject outcome = pending;
                    Suggestions completed = suggestionFuture.join();
                    JsonArray entries = new JsonArray();
                    for (var suggestion : completed.getList()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("text", suggestion.getText());
                        entry.addProperty("start", suggestion.getRange().getStart());
                        entry.addProperty("end", suggestion.getRange().getEnd());
                        if (suggestion.getTooltip() != null) entry.addProperty("tooltip", suggestion.getTooltip().getString());
                        entries.add(entry);
                    }
                    outcome.add("suggestions", entries);
                    outcome.addProperty("status", "COMPLETED");
                    outcome.addProperty("source", "actual-client-received-server-dispatcher-and-suggestions-provider");
                    suggestionFuture = null; pending = null;
                    finish(mc, outcome, false);
                } else if (System.nanoTime() > pendingDeadline) throw new IOException("Client completion future timed out");
                else return;
            }
            Path control = gameDir.resolve("r36-control.json");
            if (Files.exists(control)) {
                JsonObject command = readObject(control, MAX_CONTROL_BYTES);
                long sequence = command.get("sequence").getAsLong();
                if (sequence > lastSequence) {
                    if (!ownerToken.equals(command.get("ownerToken").getAsString()) || sequence > 1000000L)
                        throw new IOException("Control owner/sequence mismatch");
                    lastSequence = sequence; // Exactly once, including an operation which fails.
                    perform(mc, command);
                }
            }
            if (ticks % 20 == 0 && lastResult != null) writeState(mc, lastResult);
        } catch (Exception | LinkageError error) {
            suggestionFuture = null; pending = null;
            JsonObject failure = result(lastSequence, "control", "ERROR");
            failure.addProperty("error", error.toString());
            LOG.error("[r36-multiplayer] owned control failed", error);
            try { finish(mc, failure, false); }
            catch (Exception nested) { LOG.error("[r36-multiplayer] evidence failure", nested); active = false; }
        }
    }

    private static void perform(Minecraft mc, JsonObject control) throws Exception {
        String operation = control.get("operation").getAsString();
        JsonObject outcome = result(lastSequence, operation, "COMPLETED");
        switch (operation) {
            case "command" -> {
                requireConnection(mc);
                String text = boundedText(control, "command");
                if (text.startsWith("/")) text = text.substring(1);
                mc.getConnection().sendCommand(text);
                outcome.addProperty("command", text);
                outcome.addProperty("status", "SENT_NOT_ACCEPTANCE");
                finish(mc, outcome, false);
            }
            case "snapshot" -> finish(mc, outcome, control.has("screenshot") && control.get("screenshot").getAsBoolean());
            case "suggest" -> {
                requireConnection(mc);
                String input = boundedText(control, "input");
                var dispatcher = mc.getConnection().getCommands();
                var parsed = dispatcher.parse(input, mc.getConnection().getSuggestionsProvider());
                JsonArray parsedNodes = new JsonArray();
                for (var node : parsed.getContext().getNodes()) parsedNodes.add(describeNode(node.getNode(), node.getNode().getName()));
                outcome.add("parsedNodes", parsedNodes);
                outcome.addProperty("input", input);
                outcome.addProperty("unread", parsed.getReader().getRemaining());
                suggestionFuture = dispatcher.getCompletionSuggestions(parsed);
                pending = outcome;
                pendingDeadline = System.nanoTime() + 20_000_000_000L;
            }
            case "disconnect" -> {
                requireConnection(mc);
                mc.disconnect(new TitleScreen());
                finish(mc, outcome, false);
            }
            case "connect" -> {
                if (mc.getConnection() != null) throw new IOException("Disconnect before reconnecting");
                String endpoint = "127.0.0.1:" + port;
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(endpoint),
                        new ServerData("R36 owned loopback", endpoint, ServerData.Type.OTHER), false, null);
                outcome.addProperty("status", "CONNECT_REQUESTED_NOT_ACCEPTANCE");
                finish(mc, outcome, false);
            }
            case "stop" -> {
                if (mc.getConnection() != null) requireConnection(mc);
                finish(mc, outcome, false);
                active = false;
                mc.stop();
            }
            default -> throw new IOException("Unknown owned operation " + operation);
        }
    }

    private static String boundedText(JsonObject control, String key) throws IOException {
        String text = control.get(key).getAsString();
        if (text.isEmpty() || text.length() > 2048 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0)
            throw new IOException("Invalid command/completion input");
        return text;
    }

    private static boolean ownedConnection(Minecraft mc) {
        if (mc.getConnection() == null) return false;
        var remote = mc.getConnection().getConnection().getRemoteAddress();
        return remote instanceof InetSocketAddress address && address.getAddress() != null
                && address.getAddress().getHostAddress().equals("127.0.0.1") && address.getPort() == port;
    }

    private static void requireConnection(Minecraft mc) throws IOException {
        if (!ownedConnection(mc) || mc.player == null || !mc.player.getGameProfile().getName().equals(playerName))
            throw new IOException("Not the declared loopback server/player connection");
    }

    private static JsonObject result(long sequence, String operation, String status) {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("ownerToken", ownerToken);
        object.addProperty("sequence", sequence);
        object.addProperty("operation", operation);
        object.addProperty("status", status);
        return object;
    }

    private static JsonObject snapshot(Minecraft mc) {
        JsonObject state = new JsonObject();
        state.addProperty("gameDir", gameDir.toString());
        state.addProperty("playerName", playerName);
        state.addProperty("connected", ownedConnection(mc));
        state.addProperty("enforced", SkyboxServerState.enforced());
        var settings = SkyboxServerState.get();
        state.addProperty("revision", settings.revision());
        state.add("environment", JSON.toJsonTree(settings.environment().overrides()));
        state.addProperty("settingsWire", SkyboxConfigPayload.encodeSettings(settings));
        state.addProperty("clockMode", settings.clock().mode().id());
        state.addProperty("clockBase", settings.clock().ticks());
        state.addProperty("clockAnchor", settings.clockAnchor());
        state.addProperty("clientJvmSkyColor", System.getProperty("skyboxblock.environment.visual/sky_color", "<absent>"));
        state.addProperty("nativeDayTime", mc.level == null ? -1 : mc.level.getDayTime());
        state.addProperty("nativeGameTime", mc.level == null ? -1 : mc.level.getGameTime());
        if (mc.level != null) state.addProperty("dimension", mc.level.dimension().location().toString());
        if (mc.player != null) state.add("playerPosition", JSON.toJsonTree(new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()}));
        state.addProperty("messageCounter", messageCounter);
        JsonArray chat = new JsonArray();
        messages.forEach(message -> chat.add(message.deepCopy()));
        state.add("messages", chat);
        state.add("commandTree", commandTree(mc));
        JsonObject visual = new JsonObject();
        var frame = SkyWorldRenderer.lastRoot();
        visual.addProperty("present", frame != null && frame.level == mc.level && frame.sky != null && mc.level != null);
        if (frame != null && frame.level == mc.level && frame.sky != null && mc.level != null) {
            visual.addProperty("rootTransaction", frame.rootTransaction);
            visual.addProperty("skyBlocks", frame.blocks());
            for (String name : new String[]{"displayTicks", "phase", "nativePhase", "skyColor", "cloudColor", "starBrightness", "gameTime", "dayTime", "skyClockMode", "timeSource"}) {
                try {
                    Object value = frame.sky.getClass().getField(name).get(frame.sky);
                    if (value instanceof net.minecraft.world.phys.Vec3 vector)
                        visual.add(name, JSON.toJsonTree(new double[]{vector.x, vector.y, vector.z}));
                    else visual.add(name, JSON.toJsonTree(value));
                }
                catch (ReflectiveOperationException absent) { visual.addProperty(name + "Error", absent.toString()); }
            }
        }
        state.add("visual", visual);
        return state;
    }

    private static JsonArray commandTree(Minecraft mc) {
        JsonArray nodes = new JsonArray();
        if (!ownedConnection(mc)) return nodes;
        CommandNode<?> root = mc.getConnection().getCommands().getRoot().getChild("skyboxblock");
        if (root == null) return nodes;
        ArrayDeque<CommandNode<?>> queue = new ArrayDeque<>();
        ArrayDeque<String> paths = new ArrayDeque<>();
        queue.add(root); paths.add("skyboxblock");
        while (!queue.isEmpty() && nodes.size() < 512) {
            CommandNode<?> node = queue.removeFirst(); String path = paths.removeFirst();
            nodes.add(describeNode(node, path));
            for (CommandNode<?> child : node.getChildren()) { queue.add(child); paths.add(path + " " + child.getName()); }
        }
        return nodes;
    }

    private static JsonObject describeNode(CommandNode<?> node, String path) {
        JsonObject out = new JsonObject();
        out.addProperty("path", path);
        out.addProperty("name", node.getName());
        out.addProperty("nodeClass", node.getClass().getName());
        out.addProperty("executable", node.getCommand() != null);
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            out.addProperty("argumentType", argument.getType().getClass().getName());
            out.addProperty("argumentKind", argument.getType() instanceof net.minecraft.commands.arguments.ResourceLocationArgument
                    ? "resource_location" : argument.getType() instanceof com.mojang.brigadier.arguments.StringArgumentType
                    ? "brigadier_string" : "unclassified");
        }
        return out;
    }

    private static void finish(Minecraft mc, JsonObject outcome, boolean screenshot) throws Exception {
        if (screenshot) {
            requireConnection(mc);
            JsonArray captures = new JsonArray();
            Path image = evidence.resolve(String.format("step-%06d-screen.png", lastSequence));
            try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(image); }
            captures.add(gameDir.relativize(image).toString());
            Class<?> scene = Class.forName("dev.yg.skyboxblock.client.VanillaSkyScene");
            for (String fieldName : new String[]{"target", "displayTarget"}) {
                var field = scene.getDeclaredField(fieldName); field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof RenderTarget target && target.width > 0 && target.height > 0) {
                    Path raw = evidence.resolve(String.format("step-%06d-%s.png", lastSequence, fieldName));
                    try (NativeImage pixels = Screenshot.takeScreenshot(target)) { pixels.writeToFile(raw); }
                    captures.add(gameDir.relativize(raw).toString());
                }
            }
            outcome.add("screenshots", captures);
        }
        outcome.add("snapshot", snapshot(mc));
        outcome.addProperty("nanoTime", System.nanoTime());
        Path step = evidence.resolve(String.format("step-%06d.json", lastSequence));
        if (!Files.exists(step)) Files.writeString(step, JSON.toJson(outcome) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        lastResult = outcome;
        writeState(mc, outcome);
    }

    private static void writeState(Minecraft mc, JsonObject outcome) throws IOException {
        JsonObject copy = outcome.deepCopy();
        copy.add("snapshot", snapshot(mc));
        copy.addProperty("heartbeatTick", ticks);
        Path destination = gameDir.resolve("r36-client-state.json");
        if (Files.isSymbolicLink(destination)) throw new IOException("State path is a symbolic link");
        Path temporary = gameDir.resolve(".r36-state-" + ownerToken + ".tmp");
        Files.writeString(temporary, JSON.toJson(copy) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonObject readObject(Path path, int limit) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Owned JSON is a symbolic link");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(limit + 1); }
        if (bytes.length > limit) throw new IOException("Owned JSON exceeds limit");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}