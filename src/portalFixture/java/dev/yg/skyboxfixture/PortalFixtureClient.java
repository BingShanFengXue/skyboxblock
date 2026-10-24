package dev.yg.skyboxfixture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Standalone Fabric client fixture. No production sky renderer, block registration or shader hooks.
 * All world mutations are on the integrated-server thread and only after an explicit owned-gameDir check.
 */
public final class PortalFixtureClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("skybox-portal-fixture");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final boolean ENABLED = Boolean.getBoolean("skyboxfixture.enabled");
    private static final String VARIANT = System.getProperty("skyboxfixture.variant", "baseline");
    private static final String MODE = System.getProperty("skyboxfixture.mode", "portal");
    private static boolean directCaptureVerified;
    private static final int SETTLE = Math.max(30, Integer.getInteger("skyboxfixture.settleTicks", 90));
    private static final int MOVE_TIMEOUT = Math.max(80, Integer.getInteger("skyboxfixture.moveTimeoutTicks", 200));
    private static final List<Scene> SCENES = new ArrayList<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static final List<String> COMMANDS = new ArrayList<>();
    private static final List<JsonObject> MOVEMENTS = new ArrayList<>();
    private static final List<String> CAPTURED = new ArrayList<>();
    private static Entity forwardPortal, reversePortal; // Server-thread-owned; never read by rendering callbacks.
    private static JsonObject portalState = new JsonObject(), setupResult = new JsonObject();
    private static JsonObject serverAtCapture = new JsonObject();
    private static CompletableFuture<JsonObject> work, captureQuery;
    private static Scene scene;
    private static Motion motion;
    private static RootFrame root;
    private static Path output, ownedGameDir;
    private static boolean active, ownershipChecked, ownershipRejected, controlsOwned;
    private static boolean pendingCapture, forwardComplete, returnComplete;
    private static int startupTicks, sceneIndex = -1, remaining, ticks, frameSerial, gameDepth;
    private static int resetTeleportCount, finishTicks = -1, taskWaitTicks, screenshotWaitTicks;
    private static float lockedYaw, lockedPitch;

    @Override public void onInitializeClient() {
        if (!ENABLED) return;
        if (!List.of("baseline", "empty", "sky").contains(VARIANT)) {
            LOG.error("[skybox-portal-fixture] invalid variant: {}", VARIANT);
            return;
        }
        if (!List.of("portal", "remote-direct").contains(MODE)) {
            LOG.error("[skybox-portal-fixture] invalid mode: {}", MODE);
            return;
        }
        scenes();
        ClientTickEvents.END_CLIENT_TICK.register(PortalFixtureClient::tick);
        LOG.info("[skybox-portal-fixture] ENABLED variant={} scenes={} (requires -Dskyboxfixture.gameDir=<isolated gameDir>)",
                VARIANT, SCENES.size());
    }

    private static void scenes() {
        if (MODE.equals("remote-direct")) {
            SCENES.add(new Scene("00-remote-direct", SETTLE, mc -> {
                RemoteDirectControl.load(ownedGameDir, VARIANT);
                lockedYaw = RemoteDirectControl.yaw(); lockedPitch = RemoteDirectControl.pitch();
                return serverTask(mc, (server, player) -> {
                    RemoteDirectControl.prepare(server, player, VARIANT);
                    Vec3 feet = RemoteDirectControl.feet();
                    resetPosition(player, server.overworld(), feet.x, feet.y, feet.z);
                    return portalStateOnServer("remote-direct-no-portals-created");
                });
            }));
            return;
        }
        SCENES.add(new Scene("00-no-portal", SETTLE, mc -> {
            lockLook(.5, 140, -3, .5, 143, 2);
            return serverTask(mc, (server, player) -> {
                ServerLevel level = server.overworld();
                discardOwnedPortals();
                command(server, player, "gamemode creative @s");
                command(server, player, "gamerule doDaylightCycle false");
                command(server, player, "gamerule doWeatherCycle false");
                command(server, player, "gamerule doMobSpawning false");
                command(server, player, "weather clear");
                command(server, player, "time set 6000");
                command(server, player, "fill -12 140 -12 48 148 12 minecraft:air");
                command(server, player, "fill -12 139 -12 48 139 12 minecraft:bedrock");
                command(server, player, "fill 29 140 8 35 146 8 minecraft:white_concrete");
                for (int x = 29; x <= 35; x += 2)
                    command(server, player, "fill " + x + " 140 8 " + x + " 146 8 minecraft:red_concrete");
                command(server, player, "fill 32 140 4 32 143 4 minecraft:stone");
                if (VARIANT.equals("sky")) {
                    ResourceLocation sky = ResourceLocation.parse("skyboxblock:sky_block");
                    if (!BuiltInRegistries.BLOCK.containsKey(sky)) throw new IllegalStateException("sky variant requires skyboxblock:sky_block");
                    command(server, player, "fill 29 140 6 35 146 6 skyboxblock:sky_block");
                }
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                resetPosition(player, level, .5, 140, -3);
                return portalStateOnServer("layout-ready");
            });
        }));
        SCENES.add(new Scene("01-single-front", SETTLE, mc -> {
            lockLook(.5, 140, -3, .5, 143, 2);
            return serverTask(mc, (server, player) -> {
                forwardPortal = PortalReflection.createForward(server.overworld());
                resetPosition(player, server.overworld(), .5, 140, -3);
                return portalStateOnServer("forward-created");
            });
        }));
        SCENES.add(new Scene("02-single-oblique", SETTLE, mc -> {
            lockLook(-3.5, 140, -3, .5, 143, 2);
            return serverTask(mc, (server, player) -> {
                resetPosition(player, server.overworld(), -3.5, 140, -3);
                return portalStateOnServer("single-oblique");
            });
        }));
        SCENES.add(new Scene("03-single-near", SETTLE, mc -> {
            lockLook(.5, 140, .5, .5, 143, 2);
            return serverTask(mc, (server, player) -> {
                resetPosition(player, server.overworld(), .5, 140, .5);
                return portalStateOnServer("single-near");
            });
        }));
        SCENES.add(new Scene("04-through-before", SETTLE, mc -> {
            lockedYaw = 0; lockedPitch = 0;
            return serverTask(mc, (server, player) -> {
                if (forwardPortal == null || !forwardPortal.isAlive()) throw new IllegalStateException("forward portal is absent");
                reversePortal = PortalReflection.createReverse(forwardPortal);
                resetPosition(player, server.overworld(), .5, 140, -.75);
                return portalStateOnServer("reverse-created-before-actual-walk");
            });
        }));
        SCENES.add(new Scene("05-through-after", SETTLE, mc -> {
            beginMotion(mc, false);
            return CompletableFuture.completedFuture(copy(portalState));
        }));
        SCENES.add(new Scene("06-return-before", 35, mc -> {
            // Turn around at the ACTUAL arrival position. No teleport/reset between the two walks.
            lockedYaw = 180; lockedPitch = 0;
            return CompletableFuture.completedFuture(copy(portalState));
        }));
        SCENES.add(new Scene("07-return-after", SETTLE, mc -> {
            if (forwardComplete) beginMotion(mc, true);
            else {
                JsonObject skipped = new JsonObject();
                skipped.addProperty("direction", "return");
                skipped.addProperty("completed", false);
                skipped.addProperty("reason", "outward physical crossing did not complete; no manufactured return position");
                MOVEMENTS.add(skipped);
            }
            return CompletableFuture.completedFuture(copy(portalState));
        }));
        SCENES.add(new Scene("08-clean", SETTLE, mc -> {
            lockLook(.5, 140, -3, .5, 143, 2);
            return serverTask(mc, (server, player) -> {
                discardOwnedPortals();
                command(server, player, "fill 29 140 6 35 146 6 minecraft:air");
                resetPosition(player, server.overworld(), .5, 140, -3);
                return portalStateOnServer("cleaned");
            });
        }));
    }

    private static boolean validateOwnership(Minecraft mc, MinecraftServer server) {
        try {
            String expected = System.getProperty("skyboxfixture.gameDir", "");
            if (expected.isBlank()) throw new IllegalStateException("missing explicit skyboxfixture.gameDir ownership assertion");
            Path actual = mc.gameDirectory.toPath().toRealPath();
            Path declared = Path.of(expected).toRealPath();
            if (!actual.equals(declared)) throw new IllegalStateException("gameDir ownership assertion differs from running client");
            boolean fixturePath = false;
            for (Path component : actual)
                if (component.toString().equals("render-regression") || component.toString().equals("portal-fixture")) fixturePath = true;
            if (!fixturePath) throw new IllegalStateException("owned gameDir must be inside a render-regression or portal-fixture directory");
            String worldName = server.getWorldData().getLevelName();
            if (!worldName.equals("Skybox render fixture") && !worldName.equals("Skybox portal fixture"))
                throw new IllegalStateException("unexpected integrated-server fixture world name: " + worldName);
            ownedGameDir = actual;
            output = Path.of(System.getProperty("skyboxfixture.output", actual.resolve("results").toString())).toAbsolutePath().normalize();
            if (!output.startsWith(actual) || output.equals(actual)) throw new IllegalStateException("output must be a child of the owned fixture gameDir");
            Path existingParent = output;
            while (!Files.exists(existingParent)) existingParent = existingParent.getParent();
            if (!existingParent.toRealPath().startsWith(actual)) throw new IllegalStateException("output ancestor resolves outside owned fixture gameDir");
            Files.createDirectories(output);
            if (!output.toRealPath().startsWith(actual)) throw new IllegalStateException("output resolves outside owned fixture gameDir");
            if (!FabricLoader.getInstance().isModLoaded("immersive_portals")) throw new IllegalStateException("fixture runtime requires Immersive Portals");
            if (VARIANT.equals("baseline") && FabricLoader.getInstance().isModLoaded("skyboxblock"))
                throw new IllegalStateException("baseline requires skyboxblock to be absent, not merely disabled");
            return true;
        } catch (Exception exception) {
            LOG.error("[skybox-portal-fixture] OWNERSHIP/SETUP CHECK FAILED; no scene mutation or client exit", exception);
            ownershipRejected = true;
            return false;
        }
    }

    private static String preWorldScreen;
    private static void inspectPreWorld(Minecraft mc) {
        if (mc.screen==null) return;
        try {
            String assertion=System.getProperty("skyboxfixture.gameDir","");
            if (assertion.isEmpty()) return;
            Path actual=mc.gameDirectory.toPath().toRealPath();
            if (!actual.equals(Path.of(assertion).toRealPath()) || !actual.toString().contains("portal-fixture")) return;
            Path destination=Path.of(System.getProperty("skyboxfixture.output",actual.resolve("results").toString())).toAbsolutePath().normalize();
            if (!destination.startsWith(actual) || destination.equals(actual)) return;
            String type=mc.screen.getClass().getName();
            if (type.equals(preWorldScreen)) return;
            preWorldScreen=type;
            LOG.info("[skybox-portal-fixture] PREWORLD {} title={}",type,mc.screen.getTitle().getString());
            net.minecraft.client.gui.components.Button proceed=null;
            for (var child:mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button) {
                LOG.info("[skybox-portal-fixture] PREWORLD button={}",button.getMessage());
                if (button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("selectWorld.backupJoinSkipButton")) proceed=button;
            }
            Files.createDirectories(destination);
            try (CaptureState ignored=new CaptureState(); NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(destination.resolve("preworld-"+mc.screen.getClass().getSimpleName()+".png"));
            }
            if (proceed!=null) {
                LOG.info("[skybox-portal-fixture] continue owned NEW experimental fixture without redundant backup");
                proceed.onPress();
            }
        } catch (IOException exception) { throw new RuntimeException(exception); }
    }

    private static void tick(Minecraft mc) {
        if (!ENABLED || ownershipRejected) return;
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) { inspectPreWorld(mc); return; }
        MinecraftServer server = mc.getSingleplayerServer();
        if (!ownershipChecked) {
            ownershipChecked = true;
            if (!validateOwnership(mc, server)) return;
            active = true;
            controlsOwned = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.hideGui = true;
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            mc.options.renderDistance().set(8);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (MODE.equals("remote-direct")) mc.gameRenderer.setRenderHand(false); // Match the audited IP child doRenderHand=false.
            LOG.info("[skybox-portal-fixture] OWNED gameDir={} output={} variant={}", ownedGameDir, output, VARIANT);
        }
        if (mc.screen != null) mc.setScreen(null);
        if (finishTicks >= 0) {
            releaseMovement(mc);
            if (++finishTicks == 30 && Boolean.parseBoolean(System.getProperty("skyboxfixture.exit", "true"))) {
                // Ownership was checked against the actual gameDir and this integrated fixture world.
                if (mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(ownedGameDir)) mc.stop();
            }
            return;
        }
        ticks++;
        lockCamera(mc);
        if (++startupTicks < 60) return;
        if (pendingCapture) {
            if (++screenshotWaitTicks > 240) {
                error("root screenshot did not occur for " + scene.name(), null);
                pendingCapture = false;
                System.clearProperty("skyboxfixture.captureScene");
                scene = null;
            }
            return;
        }
        if (work != null) {
            if (!work.isDone()) {
                if (++taskWaitTicks > 400) failTask("scene server task timeout");
                return;
            }
            try {
                setupResult = work.join();
                portalState = copy(setupResult);
            } catch (RuntimeException exception) { error("scene setup failed: " + scene.name(), exception); }
            work = null;
            taskWaitTicks = 0;
            remaining = scene.warmupTicks();
            return;
        }
        if (motion != null) {
            advanceMotion(mc);
            return;
        }
        releaseMovement(mc);
        if (remaining > 0) { remaining--; return; }
        if (captureQuery != null) {
            if (!captureQuery.isDone()) {
                if (++taskWaitTicks > 400) failTask("capture server query timeout");
                return;
            }
            try { serverAtCapture = captureQuery.join(); }
            catch (RuntimeException exception) { error("server capture snapshot failed", exception); serverAtCapture = new JsonObject(); }
            captureQuery = null;
            taskWaitTicks = 0;
            validateMotionOnServer();
            pendingCapture = true;
            System.setProperty("skyboxfixture.captureScene",scene.name());
            screenshotWaitTicks = 0;
            return;
        }
        if (scene != null) {
            captureQuery = serverTask(mc, (runningServer, player) -> {
                JsonObject snapshot = playerState(player);
                if (MODE.equals("remote-direct")) snapshot.add("remoteDirectWorld",
                        RemoteDirectControl.inspectWorld(runningServer.overworld(), VARIANT));
                else if (scene != null && List.of("01-single-front", "02-single-oblique", "03-single-near", "04-through-before").contains(scene.name()))
                    snapshot.add("actualWorldGeometry", RemoteDirectControl.inspectWorld(runningServer.overworld(), VARIANT));
                return snapshot;
            });
            return;
        }
        if (++sceneIndex >= SCENES.size()) { finish(mc); return; }
        scene = SCENES.get(sceneIndex);
        setupResult = new JsonObject();
        LOG.info("[skybox-portal-fixture] SCENE {} variant={}", scene.name(), VARIANT);
        try { work = scene.action().run(mc); }
        catch (Exception exception) {
            error("scene dispatch failed: " + scene.name(), exception);
            work = CompletableFuture.completedFuture(new JsonObject());
        }
    }

    private static void failTask(String message) {
        error(message + " scene=" + (scene == null ? "none" : scene.name()), null);
        // Never race a still-running world mutation with the next scene.
        work = null;
        captureQuery = null;
        if (motion != null) finishMotion(Minecraft.getInstance(), false, message);
        finish(Minecraft.getInstance());
    }

    private static void beginMotion(Minecraft mc, boolean returning) {
        releaseMovement(mc);
        lockedYaw = returning ? 180 : 0;
        lockedPitch = 0;
        motion = new Motion(returning, playerState(mc), resetTeleportCount);
        LOG.info("[skybox-portal-fixture] WALK START returning={} position={}", returning, mc.player.position());
    }

    private static void advanceMotion(Minecraft mc) {
        Motion step = motion;
        step.age++;
        lockCamera(mc);
        mc.player.setSprinting(false);
        mc.options.keyUp.setDown(true);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        JsonObject sample = playerState(mc);
        sample.addProperty("walkTick", step.age);
        step.samples.add(sample);
        double x = mc.player.getX(), z = mc.player.getZ();
        double deltaX = x - step.lastX;
        if (step.returning ? deltaX < -24 : deltaX > 24) step.translationObserved = true;
        step.lastX = x;
        if (step.returning ? (x > -3 && x < 4 && z < 1.2) : (x > 29 && x < 36 && z > 2.8)) {
            finishMotion(mc, true, "client reached the opposite portal side by forward-key movement");
        } else if (step.age >= MOVE_TIMEOUT) {
            finishMotion(mc, false, "physical crossing timed out; no teleport fallback was applied");
        }
    }

    private static void finishMotion(Minecraft mc, boolean reached, String reason) {
        Motion step = motion;
        if (step == null) return;
        releaseMovement(mc);
        JsonObject result = new JsonObject();
        result.addProperty("direction", step.returning ? "return" : "outward");
        result.addProperty("movementMechanism", "client keyUp; camera rotation locked; no position lock or teleport during walk");
        result.addProperty("completed", false); // Only promoted after the server confirms the same arrival.
        result.addProperty("clientReachedOppositeSide", reached);
        result.addProperty("reason", reason);
        result.addProperty("ticks", step.age);
        result.addProperty("resetTeleportCountBefore", step.teleportsBefore);
        result.addProperty("resetTeleportCountAfter", resetTeleportCount);
        result.addProperty("noResetTeleportDuringWalk", step.teleportsBefore == resetTeleportCount);
        result.addProperty("startSideCorrect", step.startSideCorrect);
        result.addProperty("portalTranslationObserved", step.translationObserved);
        result.add("before", step.before);
        result.add("after", playerState(mc));
        result.add("samples", step.samples);
        MOVEMENTS.add(result);
        motion = null;
        remaining = SETTLE;
        if (!reached) error(reason, null);
        LOG.info("[skybox-portal-fixture] WALK END returning={} clientReached={} position={}", step.returning, reached, mc.player.position());
    }

    private static void validateMotionOnServer() {
        if (MOVEMENTS.isEmpty() || scene == null
                || !(scene.name().equals("05-through-after") || scene.name().equals("07-return-after"))) return;
        JsonObject result = MOVEMENTS.getLast();
        if (!result.has("clientReachedOppositeSide")) return;
        boolean returning = result.get("direction").getAsString().equals("return");
        boolean sameDimension = serverAtCapture.has("dimension")
                && serverAtCapture.get("dimension").getAsString().equals("minecraft:overworld");
        double serverX = serverAtCapture.has("position") ? serverAtCapture.getAsJsonArray("position").get(0).getAsDouble() : Double.NaN;
        double serverZ = serverAtCapture.has("position") ? serverAtCapture.getAsJsonArray("position").get(2).getAsDouble() : Double.NaN;
        boolean serverArrived = sameDimension && (returning
                ? serverX > -3 && serverX < 4 && serverZ < 1.2
                : serverX > 29 && serverX < 36 && serverZ > 2.8);
        boolean completed = result.get("clientReachedOppositeSide").getAsBoolean()
                && result.get("startSideCorrect").getAsBoolean()
                && result.get("portalTranslationObserved").getAsBoolean()
                && result.get("noResetTeleportDuringWalk").getAsBoolean() && serverArrived;
        result.add("serverAfter", copy(serverAtCapture));
        result.addProperty("serverConfirmedOppositeSide", serverArrived);
        result.addProperty("completed", completed);
        if (returning) returnComplete = completed; else forwardComplete = completed;
        if (!completed) error("server did not confirm physical " + (returning ? "return" : "outward") + " crossing", null);
    }

    private static void releaseMovement(Minecraft mc) {
        if (!controlsOwned) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private static void lockLook(double x, double y, double z, double targetX, double targetY, double targetZ) {
        double dx = targetX - x, dy = targetY - (y + 1.62), dz = targetZ - z;
        lockedYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        lockedPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    private static void lockCamera(Minecraft mc) {
        if (!active || mc.player == null) return;
        mc.player.setYRot(lockedYaw); mc.player.yRotO = lockedYaw;
        mc.player.setXRot(lockedPitch); mc.player.xRotO = lockedPitch;
        mc.player.yHeadRot = lockedYaw; mc.player.yHeadRotO = lockedYaw;
    }

    private static CompletableFuture<JsonObject> serverTask(Minecraft mc, ServerAction action) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        MinecraftServer server = mc.getSingleplayerServer();
        UUID playerId = mc.player.getUUID();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("fixture server player is absent");
                result.complete(action.run(server, player));
            } catch (Exception exception) { result.completeExceptionally(exception); }
        });
        return result;
    }

    static void command(MinecraftServer server, ServerPlayer player, String text) {
        COMMANDS.add(text);
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4).withSuppressedOutput(), text);
    }

    private static void resetPosition(ServerPlayer player, ServerLevel level, double x, double y, double z) {
        resetTeleportCount++;
        player.teleportTo(level, x, y, z, lockedYaw, lockedPitch);
    }

    private static void discardOwnedPortals() {
        if (forwardPortal != null && forwardPortal.getTags().contains(PortalReflection.TAG)) forwardPortal.discard();
        if (reversePortal != null && reversePortal.getTags().contains(PortalReflection.TAG)) reversePortal.discard();
        forwardPortal = reversePortal = null;
    }

    private static JsonObject portalStateOnServer(String stage) {
        JsonObject result = new JsonObject();
        result.addProperty("stage", stage);
        result.addProperty("forwardCreated", forwardPortal != null && forwardPortal.isAlive());
        result.addProperty("reverseCreated", reversePortal != null && reversePortal.isAlive());
        result.add("forward", PortalReflection.portalDescription(forwardPortal));
        result.add("reverse", PortalReflection.portalDescription(reversePortal));
        result.addProperty("targetWall", VARIANT.equals("sky") && !stage.equals("cleaned")
                ? "skyboxblock:sky_block at 29..35,140..146,6" : "air at 29..35,140..146,6");
        result.addProperty("behindWall", "white/red stripes at z=8");
        result.addProperty("resetTeleportCount", resetTeleportCount);
        return result;
    }

    public static void beginGameView() {
        if (!active || finishTicks >= 0) return;
        if (gameDepth++ == 0) {
            lockCamera(Minecraft.getInstance());
            root = new RootFrame(++frameSerial);
        }
    }

    public static void observeWorldView(Camera camera, Matrix4f view, Matrix4f projection) {
        if (!active || root == null || gameDepth < 1 || finishTicks >= 0) return;
        Minecraft mc = Minecraft.getInstance();
        JsonObject observation = PortalReflection.renderingState();
        observation.addProperty("stage", "LevelRenderer.renderLevel.HEAD");
        observation.addProperty("gameRendererDepth", gameDepth);
        observation.addProperty("dimension", mc.level == null ? "null" : mc.level.dimension().location().toString());
        observation.add("camera", vector(camera.getPosition()));
        observation.add("view", matrix(view));
        observation.add("projection", matrix(projection));
        observation.add("gl", readGlState());
        if (root.views.size() < 64) root.views.add(observation);
        if (gameDepth == 1 && !PortalReflection.shadowRendering() && root.rootView == null) root.rootView = observation.deepCopy();
    }

    public static void observeRaster() {
        if (!active || root==null || gameDepth<1 || finishTicks>=0) return;
        for (int i=root.views.size()-1;i>=0;i--) {
            JsonObject observation=root.views.get(i).getAsJsonObject();
            if (observation.get("gameRendererDepth").getAsInt()!=gameDepth) continue;
            observation.add("rasterHints",PortalReflection.rasterHints());
            if (gameDepth==1 && !observation.get("irisShadow").getAsBoolean()) root.rootView=observation.deepCopy();
            return;
        }
    }

    public static void endGameView() {
        if (!active || finishTicks >= 0 || gameDepth <= 0) return;
        try {
            if (gameDepth == 1 && pendingCapture && scene != null && root != null && !PortalReflection.portalRendering()) capture();
        } finally {
            if (--gameDepth == 0) root = null;
        }
    }

    private static void capture() {
        Minecraft mc = Minecraft.getInstance();
        String name = scene.name();
        if (root.rootView == null) error("root camera/projection observation is missing for " + name, null);
        int portalViews = 0;
        for (var observation : root.views)
            if (observation.getAsJsonObject().has("portalRendering")
                    && observation.getAsJsonObject().get("portalRendering").getAsBoolean()) portalViews++;
        JsonObject result = baseMetadata(mc);
        result.addProperty("scene", name);
        result.addProperty("portalViewCount", portalViews);
        result.addProperty("portalViewObserved", portalViews > 0);
        result.addProperty("captureStage", "GameRenderer.renderLevel.RETURN, outermost tracked invocation, portal layer zero");
        result.addProperty("frameSerial", root.serial);
        result.addProperty("rootViewObserved", root.rootView != null);
        result.add("rootView", root.rootView == null ? new JsonObject() : root.rootView);
        result.add("views", root.views);
        JsonArray skyViews=new JsonArray();
        if (FabricLoader.getInstance().isModLoaded("skyboxblock")) {
            try {
                Object evidence=Class.forName("dev.yg.skyboxblock.client.PortalViewCapture").getMethod("describe").invoke(null);
                if (evidence instanceof JsonArray array) skyViews=array;
            } catch (ReflectiveOperationException exception) { error("optional sky view evidence unavailable",exception); }
        }
        result.add("skyViews",skyViews);
        result.add("rootReturnRenderState", PortalReflection.renderingState());
        result.add("glBeforeScreenshot", readGlState());
        result.add("serverPlayer", copy(serverAtCapture));
        if (MODE.equals("remote-direct")) {
            JsonObject direct = RemoteDirectControl.capture(root.rootView, root.views, serverAtCapture,
                    mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
            directCaptureVerified = direct.get("verified").getAsBoolean();
            result.add("remoteDirect", direct);
            if (!directCaptureVerified) error("remote-direct pose/matrix/geometry/no-portal evidence did not verify", null);
        }
        result.add("portalCreation", copy(portalState));
        result.add("sceneSetup", copy(setupResult));
        result.addProperty("actualThroughCompleted", forwardComplete);
        result.addProperty("actualReturnCompleted", returnComplete);
        result.add("movementEvidence", GSON.toJsonTree(MOVEMENTS));
        result.add("commandsExecuted", GSON.toJsonTree(COMMANDS));
        result.add("errors", GSON.toJsonTree(ERRORS));
        boolean imageWritten = false;
        try (CaptureState ignored = new CaptureState(); NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            result.addProperty("width", image.getWidth());
            result.addProperty("height", image.getHeight());
            image.writeToFile(output.resolve(name + ".png"));
            imageWritten = true;
        } catch (Exception exception) { error("screenshot failed: " + name, exception); }
        result.addProperty("screenshotWritten", imageWritten);
        result.add("glAfterScreenshot", readGlState());
        result.add("errors", GSON.toJsonTree(ERRORS));
        try {
            writeJson(name + ".json", result);
            CAPTURED.add(name);
            LOG.info("[skybox-portal-fixture] CAPTURED {} image={} views={} feet={} through={} return={}",
                    name, imageWritten, root.views.size(), mc.player.position(), forwardComplete, returnComplete);
        } catch (IOException exception) { error("metadata write failed: " + name, exception); }
        pendingCapture = false;
        System.clearProperty("skyboxfixture.captureScene");
        scene = null;
    }

    private static JsonObject baseMetadata(Minecraft mc) {
        JsonObject result = new JsonObject();
        result.addProperty("harnessVersion", "1.0.0");
        result.addProperty("time", Instant.now().toString());
        result.addProperty("variant", VARIANT);
        result.addProperty("mode", MODE);
        result.addProperty("gameDir", ownedGameDir.toString());
        result.addProperty("tick", ticks);
        result.addProperty("settleTicks", SETTLE);
        result.addProperty("skyboxblockLoaded", FabricLoader.getInstance().isModLoaded("skyboxblock"));
        result.addProperty("resetTeleportCount", resetTeleportCount);
        result.add("player", playerState(mc));
        JsonObject geometry=new JsonObject();
        geometry.add("skyBounds",GSON.toJsonTree(new double[][]{{29,140,6},{36,147,7}}));
        geometry.add("behindBounds",GSON.toJsonTree(new double[][]{{29,140,8},{36,147,9}}));
        geometry.add("remotePillarBounds",GSON.toJsonTree(new double[][]{{32,140,4},{33,144,5}}));
        geometry.add("sourcePortalBounds",GSON.toJsonTree(new double[][]{{-1.5,140,2},{2.5,146,2}}));
        geometry.add("translation",GSON.toJsonTree(new double[]{32,0,0}));
        result.add("fixtureGeometry",geometry);
        result.add("mods", GSON.toJsonTree(FabricLoader.getInstance().getAllMods().stream()
                .map(container -> container.getMetadata().getId() + "=" + container.getMetadata().getVersion().getFriendlyString()).sorted().toList()));
        return result;
    }

    private static void finish(Minecraft mc) {
        if (finishTicks >= 0) return;
        System.clearProperty("skyboxfixture.captureScene");
        releaseMovement(mc);
        finishTicks = 0;
        JsonObject summary = baseMetadata(mc);
        summary.addProperty("expectedScenes", SCENES.size());
        summary.addProperty("capturedScenes", CAPTURED.size());
        summary.add("scenes", GSON.toJsonTree(CAPTURED));
        summary.addProperty("actualThroughCompleted", forwardComplete);
        summary.addProperty("actualReturnCompleted", returnComplete);
        summary.add("movementEvidence", GSON.toJsonTree(MOVEMENTS));
        summary.add("errors", GSON.toJsonTree(ERRORS));
        summary.addProperty("remoteDirectVerified", MODE.equals("remote-direct") && directCaptureVerified);
        boolean success = ERRORS.isEmpty() && CAPTURED.size() == SCENES.size()
                && (MODE.equals("remote-direct") ? directCaptureVerified : forwardComplete && returnComplete);
        summary.addProperty("success", success);
        try { writeJson("summary.json", summary); }
        catch (IOException exception) { error("summary write failed", exception); success = false; }
        LOG.info("[skybox-portal-fixture] FINISHED: {} scenes success={} actualThrough={} actualReturn={}",
                CAPTURED.size(), success, forwardComplete, returnComplete);
    }

    private static void writeJson(String name, JsonObject data) throws IOException {
        Files.writeString(output.resolve(name), GSON.toJson(data) + "\n", StandardCharsets.UTF_8);
    }

    private static void error(String message, Throwable cause) {
        String detail = message + (cause == null ? "" : ": " + PortalReflection.describeError(cause));
        ERRORS.add(detail);
        if (cause == null) LOG.error("[skybox-portal-fixture] {}", detail);
        else LOG.error("[skybox-portal-fixture] " + detail, cause);
    }

    private static JsonObject playerState(Minecraft mc) {
        JsonObject result = new JsonObject();
        if (mc.player == null || mc.level == null) return result;
        result.addProperty("dimension", mc.level.dimension().location().toString());
        result.add("position", vector(mc.player.position()));
        result.add("velocity", vector(mc.player.getDeltaMovement()));
        result.addProperty("yaw", mc.player.getYRot());
        result.addProperty("pitch", mc.player.getXRot());
        result.addProperty("onGround", mc.player.onGround());
        result.addProperty("flying", mc.player.getAbilities().flying);
        return result;
    }

    private static JsonObject playerState(ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", player.level().dimension().location().toString());
        result.add("position", vector(player.position()));
        result.add("velocity", vector(player.getDeltaMovement()));
        result.addProperty("yaw", player.getYRot());
        result.addProperty("pitch", player.getXRot());
        result.addProperty("onGround", player.onGround());
        return result;
    }

    static JsonArray vector(Vec3 value) {
        JsonArray result = new JsonArray();
        result.add(value.x); result.add(value.y); result.add(value.z);
        return result;
    }

    private static JsonArray matrix(Matrix4f value) {
        JsonArray result = new JsonArray();
        for (float v : value.get(new float[16])) result.add(Float.isFinite(v) ? v : null);
        return result;
    }

    private static JsonObject copy(JsonObject object) { return object.deepCopy(); }

    private static JsonObject readGlState() {
        JsonObject result = new JsonObject();
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        result.add("viewport", GSON.toJsonTree(viewport));
        result.addProperty("drawFbo", GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        result.addProperty("readFbo", GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
        result.addProperty("depthTest", GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        result.addProperty("stencilTest", GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
        result.addProperty("stencilRef", GL11.glGetInteger(GL11.GL_STENCIL_REF));
        // glGetError is intentionally not used: a read-only observer must not consume another mod's error.
        return result;
    }

    /** Screenshot.takeScreenshot downloads a texture; restore every binding/pack setting it can touch. */
    private static final class CaptureState implements AutoCloseable {
        private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int activeBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        private final int unitZeroBinding;
        private final int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        private final int pixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        private final int[] packKeys = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH,
                GL11.GL_PACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_ROWS, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES};
        private final int[] packValues = new int[packKeys.length];
        CaptureState() {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            unitZeroBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            RenderSystem.activeTexture(active);
            GL13.glActiveTexture(active);
            for (int i = 0; i < packKeys.length; i++) {
                packValues[i] = GL11.glGetInteger(packKeys[i]);
                GlStateManager._pixelStore(packKeys[i], i == 0 ? 1 : 0);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
        @Override public void close() {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL11.glReadBuffer(readBuffer);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(unitZeroBinding);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, unitZeroBinding);
            RenderSystem.activeTexture(active);
            GL13.glActiveTexture(active);
            GlStateManager._bindTexture(activeBinding);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, activeBinding);
            for (int i = 0; i < packKeys.length; i++) GlStateManager._pixelStore(packKeys[i], packValues[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
        }
    }

    private static final class RootFrame {
        final int serial;
        final JsonArray views = new JsonArray();
        JsonObject rootView;
        RootFrame(int serial) { this.serial = serial; }
    }
    private static final class Motion {
        final boolean returning;
        final JsonObject before;
        final int teleportsBefore;
        final JsonArray samples = new JsonArray();
        final boolean startSideCorrect;
        double lastX;
        boolean translationObserved;
        int age;
        Motion(boolean returning, JsonObject before, int teleportsBefore) {
            this.returning = returning; this.before = before; this.teleportsBefore = teleportsBefore;
            lastX = before.getAsJsonArray("position").get(0).getAsDouble();
            double z = before.getAsJsonArray("position").get(2).getAsDouble();
            startSideCorrect = returning ? lastX > 29 && lastX < 36 && z > 2 : lastX > -3 && lastX < 4 && z < 2;
        }
    }
    private record Scene(String name, int warmupTicks, SceneAction action) {}
    @FunctionalInterface private interface SceneAction { CompletableFuture<JsonObject> run(Minecraft mc) throws Exception; }
    @FunctionalInterface private interface ServerAction { JsonObject run(MinecraftServer server, ServerPlayer player) throws Exception; }
}
