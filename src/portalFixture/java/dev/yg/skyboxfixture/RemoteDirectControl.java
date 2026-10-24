package dev.yg.skyboxfixture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** One optional remote-camera control. Never creates a portal and never substitutes renderer matrices. */
final class RemoteDirectControl {
    private static final Gson GSON = new Gson();
    private static JsonObject plan;
    private RemoteDirectControl() {}

    static void load(Path ownedGameDir, String variant) throws Exception {
        String configured = System.getProperty("skyboxfixture.directPlan", "");
        if (configured.isBlank()) throw new IllegalStateException("remote-direct requires an explicit directPlan");
        Path file = Path.of(configured).toRealPath();
        if (!file.startsWith(ownedGameDir) || !Files.isRegularFile(file))
            throw new IllegalStateException("directPlan must be a regular file inside the owned gameDir");
        JsonObject loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        if (loaded == null || loaded.get("schemaVersion").getAsInt() != 1
                || !"remote-direct".equals(loaded.get("mode").getAsString())
                || !variant.equals(loaded.get("variant").getAsString())
                || !"minecraft:overworld".equals(loaded.get("dimension").getAsString()))
            throw new IllegalStateException("unsupported directPlan identity");
        vector(loaded.getAsJsonArray("playerPosition"));
        vector(loaded.getAsJsonArray("camera"));
        if (!Float.isFinite(loaded.get("yaw").getAsFloat()) || !Float.isFinite(loaded.get("pitch").getAsFloat()))
            throw new IllegalStateException("nonfinite directPlan orientation");
        plan = loaded;
    }

    static float yaw() { return plan.get("yaw").getAsFloat(); }
    static float pitch() { return plan.get("pitch").getAsFloat(); }
    static Vec3 feet() { return vector(plan.getAsJsonArray("playerPosition")); }

    static void prepare(MinecraftServer server, ServerPlayer player, String variant) throws Exception {
        ServerLevel level = server.overworld();
        PortalFixtureClient.command(server, player, "gamemode creative @s");
        PortalFixtureClient.command(server, player, "gamerule doDaylightCycle false");
        PortalFixtureClient.command(server, player, "gamerule doWeatherCycle false");
        PortalFixtureClient.command(server, player, "gamerule doMobSpawning false");
        PortalFixtureClient.command(server, player, "weather clear");
        PortalFixtureClient.command(server, player, "time set 6000");
        // Same ordinary geometry as the unchanged nine-scene fixture, including the full walking floor.
        for (int cx = Math.floorDiv(-12, 16); cx <= Math.floorDiv(48, 16); cx++)
            for (int cz = Math.floorDiv(-12, 16); cz <= Math.floorDiv(12, 16); cz++) level.getChunk(cx, cz);
        PortalFixtureClient.command(server, player, "fill -12 140 -12 48 148 12 minecraft:air");
        PortalFixtureClient.command(server, player, "fill -12 139 -12 48 139 12 minecraft:bedrock");
        PortalFixtureClient.command(server, player, "fill 29 140 8 35 146 8 minecraft:white_concrete");
        for (int x = 29; x <= 35; x += 2)
            PortalFixtureClient.command(server, player, "fill " + x + " 140 8 " + x + " 146 8 minecraft:red_concrete");
        PortalFixtureClient.command(server, player, "fill 32 140 4 32 143 4 minecraft:stone");
        if (variant.equals("sky")) {
            if (!BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.ResourceLocation.parse("skyboxblock:sky_block")))
                throw new IllegalStateException("sky variant block is absent");
            PortalFixtureClient.command(server, player, "fill 29 140 6 35 146 6 skyboxblock:sky_block");
        }
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        JsonObject evidence = inspectWorld(level, variant);
        if (!evidence.get("verified").getAsBoolean())
            throw new IllegalStateException("remote-direct actual geometry/portal inventory is invalid: " + evidence);
    }

    static JsonObject inspectWorld(ServerLevel level, String variant) throws Exception {
        JsonObject result = new JsonObject();
        JsonArray samples = new JsonArray();
        boolean valid = true;
        for (int x = 29; x <= 35; x++) for (int y = 140; y <= 146; y++) {
            valid &= probe(level, samples, x, y, 8, (x % 2 == 1) ? "minecraft:red_concrete" : "minecraft:white_concrete");
            valid &= probe(level, samples, x, y, 6, variant.equals("sky") ? "skyboxblock:sky_block" : "minecraft:air");
        }
        for (int y = 140; y <= 143; y++) valid &= probe(level, samples, 32, y, 4, "minecraft:stone");
        for (int x = -12; x <= 48; x++) for (int z = -12; z <= 12; z++)
            valid &= probe(level, samples, x, 139, z, "minecraft:bedrock");
        Class<?> portalType = Class.forName("qouteall.imm_ptl.core.portal.Portal");
        JsonArray portals = new JsonArray();
        for (Entity entity : level.getAllEntities())
            if (portalType.isInstance(entity) && entity.isAlive()) portals.add(entity.getUUID().toString());
        Object global = Class.forName("qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage")
                .getMethod("getGlobalPortals", Level.class).invoke(null, level);
        int globalCount = global instanceof java.util.Collection<?> values ? values.size() : -1;
        result.add("portalEntityUuids", portals);
        result.addProperty("portalEntityCount", portals.size());
        result.addProperty("globalPortalCount", globalCount);
        result.addProperty("geometryMatchesExpected", valid);
        result.addProperty("sampleCount", samples.size());
        result.add("blocks", samples);
        result.addProperty("blockEvidenceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(samples.toString().getBytes(StandardCharsets.UTF_8))));
        result.addProperty("verified", valid && portals.isEmpty() && globalCount == 0);
        return result;
    }

    private static boolean probe(ServerLevel level, JsonArray samples, int x, int y, int z, String expected) {
        String actual = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
        JsonObject sample = new JsonObject();
        sample.add("position", GSON.toJsonTree(new int[]{x, y, z}));
        sample.addProperty("actual", actual);
        sample.addProperty("expected", expected);
        samples.add(sample);
        return expected.equals(actual);
    }

    static JsonObject capture(JsonObject rootView, JsonArray views, JsonObject serverPlayer, int width, int height) {
        JsonObject result = new JsonObject();
        result.add("plan", plan == null ? new JsonObject() : plan.deepCopy());
        boolean sameCamera = plan != null && rootView != null && close(rootView.getAsJsonArray("camera"), plan.getAsJsonArray("camera"), 1e-4);
        boolean sameView = plan != null && rootView != null && close(rootView.getAsJsonArray("view"), plan.getAsJsonArray("view"), 2e-5);
        boolean sameProjection = plan != null && rootView != null && close(rootView.getAsJsonArray("projection"), plan.getAsJsonArray("projection"), 2e-5);
        int childCount = 0;
        for (var view : views) if (view.getAsJsonObject().get("portalRendering").getAsBoolean()) childCount++;
        boolean geometry = serverPlayer.has("remoteDirectWorld")
                && serverPlayer.getAsJsonObject("remoteDirectWorld").get("verified").getAsBoolean();
        boolean size = plan != null && width == plan.get("width").getAsInt() && height == plan.get("height").getAsInt();
        boolean dimension = rootView != null && rootView.has("dimension") && rootView.get("dimension").getAsString().equals("minecraft:overworld");
        result.addProperty("sameCamera", sameCamera);
        result.addProperty("sameRawView", sameView);
        result.addProperty("sameRawProjection", sameProjection);
        result.addProperty("sameOutputSize", size);
        result.addProperty("sameDimension", dimension);
        result.addProperty("portalViewCount", childCount);
        result.addProperty("actualWorldVerified", geometry);
        result.addProperty("verified", sameCamera && sameView && sameProjection && size && dimension && geometry && childCount == 0);
        return result;
    }

    private static Vec3 vector(JsonArray array) {
        if (array == null || array.size() != 3) throw new IllegalStateException("expected a three-component vector");
        double x = array.get(0).getAsDouble(), y = array.get(1).getAsDouble(), z = array.get(2).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalStateException("nonfinite vector");
        return new Vec3(x, y, z);
    }

    private static boolean close(JsonArray a, JsonArray b, double tolerance) {
        if (a == null || b == null || a.size() != b.size()) return false;
        try {
            for (int i = 0; i < a.size(); i++) {
                double x = a.get(i).getAsDouble(), y = b.get(i).getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x - y) > tolerance) return false;
            }
            return true;
        } catch (RuntimeException ignored) { return false; }
    }
}
