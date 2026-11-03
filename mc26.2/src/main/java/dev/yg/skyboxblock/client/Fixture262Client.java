package dev.yg.skyboxblock.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in file-controlled integration fixture. No listener, commands or I/O in ordinary games. */
public final class Fixture262Client implements ClientModInitializer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("skybox-fixture");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path control;
    private static String lastId = "";
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile JsonObject pending;
    private static int waitFrames;
    private static int frames;
    private static float yawStep;
    private static boolean moving;
    private static boolean hideHud = true;
    private static java.util.concurrent.CompletableFuture<Void> reloading;
    private static final org.joml.Matrix4f actualProjection = new org.joml.Matrix4f();
    private static boolean actualProjectionValid, actualViewValid, irisMatricesValid;
    private static final org.joml.Matrix4f actualView = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f irisProjection = new org.joml.Matrix4f(), irisView = new org.joml.Matrix4f();
    public static void rememberProjection(org.joml.Matrix4f projection) {
        if (control != null) { actualProjection.set(projection); actualProjectionValid = true; actualViewValid = false; irisMatricesValid = false; }
    }
    public static void rememberView(org.joml.Matrix4fc view) {
        if (control != null) { actualView.set(view); actualViewValid = true; }
    }
    /** Independent observation of the uniforms used by the REAL opaque world shaders, not our renderer. */
    public static void rememberIrisMatrices() {
        if (control == null || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) return;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            if (!Boolean.TRUE.equals(iris.getMethod("isPackInUseQuick").invoke(null))) return;
            Class<?> type = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object state = type.getField("INSTANCE").get(null);
            irisProjection.set((org.joml.Matrix4fc) type.getMethod("getGbufferProjection").invoke(state));
            irisView.set((org.joml.Matrix4fc) type.getMethod("getGbufferModelView").invoke(state));
            irisMatricesValid = true;
        } catch (ReflectiveOperationException error) { irisMatricesValid = false; }
    }

    @Override public void onInitializeClient() {
        String directory = System.getProperty("skyboxblock.fixture.control", "");
        if (directory.isBlank()) return;
        control = Path.of(directory).toAbsolutePath().normalize();
        try { Files.createDirectories(control); }
        catch (Exception error) { throw new IllegalStateException("Fixture control directory", error); }
        ClientTickEvents.END_CLIENT_TICK.register(Fixture262Client::tick);
        LOGGER.info("[skybox-fixture] explicitly enabled: {}", control);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        mc.options.pauseOnLostFocus = false;
        try {
            var hidden = net.minecraft.client.gui.Hud.class.getDeclaredField("isHidden");
            hidden.setAccessible(true);
            hidden.setBoolean(mc.gui.hud, hideHud);
        } catch (ReflectiveOperationException ignored) { }
        if (moving) {
            mc.options.keyUp.setDown(true);
            mc.player.setYRot(mc.player.getYRot() + yawStep);
        }
        if (busy.get()) return;
        Path request = control.resolve("request.json");
        if (!Files.isRegularFile(request)) return;
        JsonObject input;
        try {
            input = JsonParser.parseString(Files.readString(request, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = input.get("id").getAsString();
            if (id.equals(lastId)) return;
            lastId = id;
            busy.set(true);
        } catch (Exception error) { return; } // An atomic rename is recommended; incomplete requests are retried.
        try {
            if (input.has("stop")) { finish(input, null); mc.stop(); return; }
            if (input.has("shader")) setShader(input.get("shader").getAsString());
            if (input.has("reload")) reloading = mc.reloadResourcePacks();
            if (input.has("hud")) hideHud = !input.get("hud").getAsBoolean();
            if (input.has("clouds")) mc.options.cloudStatus().set(input.get("clouds").getAsBoolean()
                    ? net.minecraft.client.CloudStatus.FANCY : net.minecraft.client.CloudStatus.OFF);
            if (input.has("moving")) {
                moving = input.get("moving").getAsBoolean();
                mc.options.keyUp.setDown(moving);
            }
            yawStep = input.has("yawStep") ? input.get("yawStep").getAsFloat() : 0;
            final var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    if (input.has("commands")) {
                        JsonArray outcomes = new JsonArray();
                        for (var command : input.getAsJsonArray("commands")) {
                            JsonObject outcome = new JsonObject();
                            outcome.addProperty("command", command.getAsString());
                            JsonArray messages = new JsonArray();
                            outcome.add("messages", messages);
                            var sink = new net.minecraft.commands.CommandSource() {
                                @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
                                @Override public boolean acceptsSuccess() { return true; }
                                @Override public boolean acceptsFailure() { return true; }
                                @Override public boolean shouldInformAdmins() { return false; }
                            };
                            var source = server.createCommandSourceStack().withSource(sink).withCallback((success, value) -> {
                                outcome.addProperty("success", success);
                                outcome.addProperty("result", value);
                            });
                            server.getCommands().performPrefixedCommand(source, command.getAsString());
                            outcomes.add(outcome);
                        }
                        input.add("_commands", outcomes);
                    }
                    mc.execute(() -> {
                        waitFrames = input.has("frames") ? Math.max(2, input.get("frames").getAsInt()) : 90;
                        pending = input;
                    });
                } catch (Throwable error) { mc.execute(() -> finish(input, error)); }
            });
        } catch (Throwable error) { finish(input, error); }
    }

    private static void setShader(String name) throws Exception {
        Class<?> iris;
        try { iris = Class.forName("net.irisshaders.iris.Iris"); }
        catch (ClassNotFoundException absent) { if (name.equals("off")) return; throw absent; }
        Object config = iris.getMethod("getIrisConfig").invoke(null);
        boolean enabled = !name.equals("off");
        if (enabled) config.getClass().getMethod("setShaderPackName", String.class).invoke(config, name);
        config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
        config.getClass().getMethod("save").invoke(config);
        iris.getMethod("reload").invoke(null);
    }

    public static void afterFinalFrame() {
        if (control == null) return;
        long handle = Minecraft.getInstance().getWindow().handle();
        if (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_VISIBLE) != 0)
            org.lwjgl.glfw.GLFW.glfwHideWindow(handle);
        if (Minecraft.getInstance().level == null) return;
        frames++;
        if (reloading != null) {
            if (!reloading.isDone()) return;
            var completed = reloading;
            reloading = null;
            try { completed.join(); }
            catch (RuntimeException error) {
                JsonObject input = pending; pending = null;
                if (input != null) finish(input, error);
                return;
            }
        }
        JsonObject input = pending;
        if (input == null || --waitFrames > 0) return;
        pending = null;
        if (!input.has("capture")) { finish(input, null); return; }
        String filename = input.get("capture").getAsString();
        Path imagePath = control.resolve(filename).normalize();
        if (!imagePath.startsWith(control)) { finish(input, new IllegalArgumentException("capture path")); return; }
        try {
            Files.createDirectories(imagePath.getParent());
            if (input.has("debug")) {
                Path dump = control.resolve(input.get("debug").getAsString()).normalize();
                if (!dump.startsWith(control)) throw new IllegalArgumentException("debug path");
                Object evidence = Class.forName("dev.yg.skyboxblock.client.SkyWindowRenderer")
                        .getMethod("fixtureDebug", Path.class).invoke(null, dump);
                input.add("_gpuInputs", (JsonObject) evidence);
            }
            input.add("_snapshot", snapshot(input, null));
            Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
                try (image) { image.writeToFile(imagePath); finish(input, null); }
                catch (Throwable error) { finish(input, error); }
            });
        } catch (Throwable error) { finish(input, error); }
    }

    private static JsonObject snapshot(JsonObject input, Throwable error) {
            Minecraft mc = Minecraft.getInstance();
            JsonObject result = new JsonObject();
            result.addProperty("id", input.get("id").getAsString());
            result.addProperty("ok", error == null);
            result.addProperty("frame", frames);
            if (input.has("_commands")) result.add("commands", input.get("_commands"));
            if (input.has("_gpuInputs")) result.add("gpuInputs", input.get("_gpuInputs"));
            result.addProperty("windowVisible", org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_VISIBLE) != 0);
            result.addProperty("windowFocused", org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != 0);
            result.addProperty("mouseGrabbed", mc.mouseHandler.isMouseGrabbed());
            result.addProperty("clouds", mc.options.getCloudStatus().name());
            if (mc.level != null) {
                float[] matrix = new float[16];
                mc.gameRenderer.mainCamera().getViewRotationProjectionMatrix(new org.joml.Matrix4f()).get(matrix);
                JsonArray vp = new JsonArray();
                for (float element : matrix) vp.add(element);
                result.add("cameraVP", vp);
                if (actualProjectionValid) {
                    var view = actualViewValid ? new org.joml.Matrix4f(actualView)
                            : mc.gameRenderer.mainCamera().getViewRotationMatrix(new org.joml.Matrix4f());
                    var nativeWorld = new org.joml.Matrix4f(actualProjection).mul(view);
                    var bob = new org.joml.Matrix4f().set(matrix).invert().mul(nativeWorld);
                    double nativeOffset=Math.sqrt(bob.m30()*bob.m30()+bob.m31()*bob.m31()+bob.m32()*bob.m32());
                    var projection = irisMatricesValid ? irisProjection : actualProjection;
                    var world = irisMatricesValid ? new org.joml.Matrix4f(irisProjection).mul(irisView) : nativeWorld;
                    JsonArray worldVp = new JsonArray();
                    for (float element : world.get(new float[16])) worldVp.add(element);
                    result.add("worldVP", worldVp);
                    result.addProperty("worldVPSource", irisMatricesValid ? "iris-gbuffer-uniforms" : "native-render-arguments");
                    boolean reversed=projection.m22()*projection.m33()-projection.m32()*projection.m23()>0;
                    JsonArray rayDepth=new JsonArray();rayDepth.add(reversed?1:-1);rayDepth.add(reversed?.5:0);
                    result.add("rayDepth",rayDepth);
                    double irisOffset=0;
                    if(irisMatricesValid) {
                        var origin=new org.joml.Matrix4f(irisView).invert();
                        irisOffset=Math.sqrt(origin.m30()*origin.m30()+origin.m31()*origin.m31()+origin.m32()*origin.m32());
                    }
                    result.addProperty("nativeBobOriginOffset",nativeOffset);
                    result.addProperty("irisViewOriginOffset",irisOffset);
                    result.addProperty("bobOriginOffset",Math.max(nativeOffset,irisOffset));
                }
                var eye = mc.gameRenderer.mainCamera().position();
                result.addProperty("cameraX", eye.x); result.addProperty("cameraY", eye.y); result.addProperty("cameraZ", eye.z);
            }
            if (error != null) result.addProperty("error", error.toString());
            if (input.has("capture")) result.add("capture", input.get("capture"));
            if (mc.player != null) {
                result.addProperty("x", mc.player.getX()); result.addProperty("y", mc.player.getY());
                result.addProperty("z", mc.player.getZ());
                result.addProperty("yaw", mc.player.getYRot()); result.addProperty("pitch", mc.player.getXRot());
                result.addProperty("dimension", mc.level.dimension().identifier().toString());
                result.addProperty("time", mc.level.getOverworldClockTime());
                result.addProperty("rainLevel", mc.level.getRainLevel(0));
                result.addProperty("thunderLevel", mc.level.getThunderLevel(0));
                try {
                    Class<?> authority = Class.forName("dev.yg.skyboxblock.client.SkyboxServerState");
                    result.addProperty("serverEnforced", (Boolean) authority.getMethod("enforced").invoke(null));
                    Object settings = authority.getMethod("get").invoke(null);
                    result.addProperty("serverRevision", ((Number) settings.getClass().getMethod("revision").invoke(settings)).longValue());
                } catch (ReflectiveOperationException ignored) { }
                JsonArray names = new JsonArray();
                BuiltInRegistries.BLOCK.forEach(block -> {
                    if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("skyboxblock")) return;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", BuiltInRegistries.BLOCK.getKey(block).toString());
                    entry.addProperty("block", block.getName().getString());
                    entry.addProperty("item", new ItemStack(block).getHoverName().getString());
                    names.add(entry);
                });
                result.add("names", names);
            }
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                result.addProperty("shader", String.valueOf(iris.getMethod("getCurrentPackName").invoke(null)));
                result.addProperty("shadersActive", (Boolean) iris.getMethod("isPackInUseQuick").invoke(null));
                result.addProperty("irisError", String.valueOf(iris.getMethod("getStoredError").invoke(null)));
            } catch (ReflectiveOperationException ignored) { result.addProperty("shadersActive", false); }
            return result;
    }

    private static void finish(JsonObject input, Throwable error) {
        try {
            JsonObject result = error == null && input.has("_snapshot")
                    ? input.getAsJsonObject("_snapshot") : snapshot(input, error);
            Path temporary = control.resolve("result.tmp");
            Files.writeString(temporary, JSON.toJson(result) + "\n", StandardCharsets.UTF_8);
            for (int attempt = 0; ; attempt++) {
                try { Files.move(temporary, control.resolve("result.json"), StandardCopyOption.REPLACE_EXISTING); break; }
                catch (java.nio.file.AccessDeniedException sharing) {
                    if (attempt >= 19) throw sharing;
                    Thread.sleep(5);
                }
            }
        } catch (Exception failure) { LOGGER.error("[skybox-fixture] response", failure); }
        finally { busy.set(false); }
    }
}
