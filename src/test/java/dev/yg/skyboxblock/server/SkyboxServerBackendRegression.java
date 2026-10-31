package dev.yg.skyboxblock.server;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import dev.yg.skyboxblock.SkyboxSettings;
import dev.yg.skyboxblock.network.SkyboxConfigPayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Independent backend main: no client/game boot, Gradle, world, instance or network side effects. */
public final class SkyboxServerBackendRegression {
    private static int checks;
    private static final String LEGACY = "unknown.future=preserved\n"
            + "clouds=false\nvanillaCloudHeight=256\nvanillaCloudFadeStart=32\nvanillaCloudFadeEnd=96\n"
            + "simulatedWeather=local\nlocalTimeDimensions=minecraft:the_nether\n"
            + "simulatedTimeDimensions=minecraft:overworld\n"
            + "environment.future/new_attribute=opaque-to-this-version\n";

    private SkyboxServerBackendRegression() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("skybox-r36-server-backend-").toAbsolutePath();
        try {
            defaultsAndClock();
            persistence(root.resolve("settings.properties"));
            failures(root.resolve("failure.properties"), root);
            clockPersistence(root.resolve("clock.properties"));
            codec();
            SkyboxServerConfig.clearTestFile();
            throwsType(IllegalStateException.class, () -> SkyboxServerConfig.forSource(null), "missing server without test hook");
            check(SkyboxServerConfig.gameTime(null) == 0L, "null source game time");
            near(0, SkyboxServerConfig.automaticTicks(null), "null source automatic ticks");
            System.out.println("SkyboxServerBackendRegression: PASS " + checks + " checks; isolated fixtures=" + root);
            if (args.length > 0) {
                Path report = Path.of(args[0]);
                Files.createDirectories(report.getParent());
                Files.writeString(report, "PASS: SkyboxServerBackendRegression " + checks + " checks\n",
                        StandardCharsets.UTF_8);
            }
        } finally {
            SkyboxServerConfig.clearTestFile();
            // Intentionally retain only this freshly-created synthetic fixture directory for audit.
        }
    }

    private static SkyboxServerConfig.Store store(Path file) {
        SkyboxServerConfig.installTestFile(file);
        return SkyboxServerConfig.forSource(null);
    }

    private static void defaultsAndClock() {
        SkyboxSettings defaults = SkyboxSettings.DEFAULT;
        check(defaults.clouds() && defaults.cloudHeight() == 192F, "legacy nominal cloud defaults");
        check(defaults.environment().describe().isEmpty(), "environment defaults all auto");
        near(48123.25, defaults.clockTicks(200L, .5F, 48123.25), "auto returns supplied automatic ticks");
        Set<String> local = new HashSet<>(Set.of("minecraft:the_nether"));
        SkyboxSettings snapshot = new SkyboxSettings(SkyEnvironment.EMPTY, true, 192, 128, 384, false,
                local, Set.of("minecraft:overworld"), new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING, 23990), 1000, 7);
        local.clear();
        check(snapshot.localTimeDimensions().contains("minecraft:the_nether"), "defensive immutable dimension copy");
        throwsType(UnsupportedOperationException.class, () -> snapshot.localTimeDimensions().add("test:x"), "immutable dimension view");
        check(snapshot.usesLocalTime("minecraft:the_nether", false), "explicit local dimension");
        check(!snapshot.usesLocalTime("minecraft:overworld", true), "simulated policy wins normal sky");
        near(5.5, snapshot.clockTicks(1015, .5F, 0), "running wraps at 24000");
        near(23990, snapshot.clockTicks(999, 0, 0), "rewind clamps elapsed to zero");
        near(5, snapshot.clockTicks(1015, Float.NaN, 0), "non-finite partial ignored");
        near(6, snapshot.clockTicks(1015, 9F, 0), "partial clamped");
        throwsType(IllegalArgumentException.class, () -> new SkyboxSettings(SkyEnvironment.EMPTY, true,
                Float.NaN, 128, 384, false, Set.of(), Set.of(), SkyTimeClock.Setting.AUTO, 0, 0), "reject nonfinite snapshot");
    }

    private static void persistence(Path file) throws Exception {
        Files.writeString(file, LEGACY + "environment.sky_color=#010101\n"
                + "environment.minecraft:visual/sky_color=#020202\n"
                + "environment.visual/sky_color=#030303\n", StandardCharsets.UTF_8);
        String previousJvm = System.getProperty("skyboxblock.environment.visual/sky_color");
        String previousClouds = System.getProperty("skyboxblock.clouds");
        try {
            System.setProperty("skyboxblock.environment.visual/sky_color", "#FFFFFF");
            System.setProperty("skyboxblock.clouds", "true");
            var store = store(file);
            check(store == SkyboxServerConfig.forSource(null), "one test Store identity");
            check(store.settings().environment().effective("sky_color").equals("#030303"), "canonical file key wins aliases and client JVM");
            check(!store.settings().clouds(), "client JVM legacy flag ignored");
            check(store.settings().cloudHeight() == 256 && store.settings().cloudFadeEnd() == 96, "legacy cloud values retained");
            check(store.settings().simulatedLocalWeather(), "legacy weather retained");
            store.setEnvironment("minecraft:visual/sky_color", "#AABBCC");
            check(store.settings().environment().effective("sky_color").equals("#AABBCC"), "set canonicalized color");
            check(store.settings().revision() == 1, "first successful commit revision");
            Properties saved = read(file);
            check(saved.getProperty("unknown.future").equals("preserved"), "unknown property retained");
            check(saved.getProperty("environment.future/new_attribute").equals("opaque-to-this-version"), "unknown environment key retained");
            check(!saved.containsKey("environment.sky_color") && !saved.containsKey("environment.minecraft:visual/sky_color"), "all aliases removed on set");
            check(saved.getProperty("environment.visual/sky_color").equals("#AABBCC"), "single canonical key stored");
            SkyboxSettings before = store.settings();
            byte[] beforeFile = Files.readAllBytes(file);
            throwsType(IllegalArgumentException.class, () -> store.setEnvironment("cloud_height", "NaN"), "invalid value rejected");
            throwsType(IllegalArgumentException.class, () -> store.setEnvironment("future/new_attribute", "1"), "unknown ID rejected");
            check(store.settings() == before && Arrays.equals(beforeFile, Files.readAllBytes(file)), "validation failure leaves snapshot file unchanged");
            store.resetEnvironment("sky_color");
            check(!store.settings().environment().has("sky_color"), "reset returns attribute to auto");
            store.setEnvironment("star_angle", "-720.25");
            store.setTime(6000, 100);
            store.resetAll();
            saved = read(file);
            check(store.settings().environment().describe().isEmpty() && store.settings().clock().mode() == SkyTimeClock.Mode.AUTO, "reset all known attributes and clock");
            check(saved.getProperty("clouds").equals("false") && saved.getProperty("vanillaCloudFadeEnd").equals("96"), "reset all keeps legacy keys");
            check(saved.getProperty("unknown.future").equals("preserved") && saved.containsKey("environment.future/new_attribute"), "reset all keeps unknown keys");
            check(!saved.containsKey("skyTimeMode") && !saved.containsKey("skyTimeTicks") && !saved.containsKey("skyTimeAnchor"), "clock triplet removed together");
            long revision = store.settings().revision();
            saved.setProperty("environment.visual/fog_color", "#123456");
            write(file, saved);
            store.reload();
            check(store.settings().revision() == revision + 1 && store.settings().environment().effective("fog_color").equals("#123456"), "reload applies complete snapshot and increments revision");
        } finally {
            restoreJvm("skyboxblock.environment.visual/sky_color", previousJvm);
            restoreJvm("skyboxblock.clouds", previousClouds);
        }
    }

    private static void failures(Path file, Path root) throws Exception {
        Files.writeString(file, LEGACY, StandardCharsets.UTF_8);
        var store = store(file);
        SkyboxSettings good = store.settings();
        String malformed = "clouds=true\nenvironment.visual/sky_color=#999999\nbad=" + "\\" + "uQQQQ\n";
        Files.writeString(file, malformed, StandardCharsets.UTF_8);
        byte[] damaged = Files.readAllBytes(file);
        throwsType(IOException.class, store::reload, "malformed Unicode reload fails");
        check(store.settings() == good, "reload failure retains previous snapshot identity and revision");
        throwsType(IOException.class, () -> store.setEnvironment("sky_color", "#112233"), "mutation refuses damaged on-disk file");
        check(store.settings() == good && Arrays.equals(damaged, Files.readAllBytes(file)), "damaged file never truncated or silently repaired");
        var initiallyDamaged = store(file);
        check(initiallyDamaged.settings() == SkyboxSettings.DEFAULT, "initial malformed file uses complete defaults not partially read values");
        check(Arrays.equals(damaged, Files.readAllBytes(file)), "initial malformed read leaves file untouched");
        Files.writeString(file, LEGACY + "skyTimeMode=running\nskyTimeTicks=24000\n", StandardCharsets.UTF_8);
        throwsType(IOException.class, initiallyDamaged::reload, "semantic invalid clock reload fails closed");
        check(initiallyDamaged.settings() == SkyboxSettings.DEFAULT, "semantic failure leaves defaults snapshot");
        Path blocked = root.resolve("blocked-parent");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        var unwritable = store(blocked.resolve("skyboxblock.properties"));
        SkyboxSettings before = unwritable.settings();
        throwsType(IOException.class, () -> unwritable.setTime(1000, 100), "directory creation write failure");
        check(unwritable.settings() == before && Files.readString(blocked).equals("not a directory"), "write failure leaves original snapshot and existing file");
        Path missingStart = root.resolve("legacy-short-distance.properties");
        Files.writeString(missingStart, "vanillaCloudFadeEnd=96\n", StandardCharsets.UTF_8);
        near(32, store(missingStart).settings().cloudFadeStart(), "legacy missing start uses end/3 when 128 is outside shortened end");
    }

    private static void clockPersistence(Path file) throws Exception {
        var store = store(file);
        store.setTime(23990, 1000);
        near(5.5, store.settings().clockTicks(1015, .5F, 0), "persisted running clock advances on server game time");
        store.lockTime(null, 1020, 777);
        check(store.settings().clock().mode() == SkyTimeClock.Mode.LOCKED && store.settings().clock().ticks() == 10, "lock null captures running display");
        near(10, store.settings().clockTicks(Long.MAX_VALUE, .9F, 0), "locked clock constant");
        check(store.timeDescription(1020, 0).equals("time = 10 / locked"), "concise player time feedback without anchor/revision");
        store.unlockTime(1100);
        near(20.25, store.settings().clockTicks(1110, .25F, 0), "unlock resumes from locked base and new anchor");
        Properties saved = read(file);
        check(saved.getProperty("skyTimeMode").equals("running") && saved.getProperty("skyTimeTicks").equals("10")
                && saved.getProperty("skyTimeAnchor").equals("1100"), "all clock persistence keys present");
        SkyboxSettings original = store.settings();
        SkyboxSettings restarted = store(file).settings();
        near(original.clockTicks(1110, .25F, 0), restarted.clockTicks(1110, .25F, 0), "restart with unchanged saved world gameTime has no offline advance");
        near(original.clockTicks(1200, .5F, 0), restarted.clockTicks(1200, .5F, 0), "two clients same snapshot and shared gameTime have same phase");
        store = SkyboxServerConfig.forSource(null);
        store.resetTime();
        check(store.settings().clock().mode() == SkyTimeClock.Mode.AUTO && store.settings().clockAnchor() == 0, "reset clock restores automatic mode");
        store.lockTime(null, 1300, 48123.75);
        check(store.settings().clock().ticks() == 123, "lock auto captures supplied automatic source time modulo day");
        // setTime must not break an existing lock: only the frozen tick changes.
        store.setTime(6000, 1400);
        check(store.settings().clock().mode() == SkyTimeClock.Mode.LOCKED && store.settings().clock().ticks() == 6000,
                "setTime keeps lock and updates its tick");
        check(store.timeDescription(1400, 0).equals("time = 6000 / locked"), "locked description after set");
        store.setTime(9000, 1500);
        near(9000, store.settings().clockTicks(Long.MAX_VALUE, .9F, 0), "locked clock still constant after set");
        store.unlockTime(1600);
        near(9000, store.settings().clockTicks(1600, 0, 0), "unlock after set resumes from the set tick");
        check(store.settings().clock().mode() == SkyTimeClock.Mode.RUNNING, "unlock leaves running mode after set");
        store.setTime(3000, 1700);
        check(store.settings().clock().mode() == SkyTimeClock.Mode.RUNNING && store.settings().clock().ticks() == 3000,
                "setTime keeps running mode when not locked");
        near(3000.5, store.settings().clockTicks(1700, .5F, 0), "running clock after set starts at the set tick");
        long revision = store.settings().revision();
        var target = store;
        throwsType(IllegalArgumentException.class, () -> target.setTime(-1, 1), "negative time rejected");
        throwsType(IllegalArgumentException.class, () -> target.setTime(24000, 1), "time 24000 rejected");
        throwsType(IllegalArgumentException.class, () -> target.setTime(0, -1), "negative anchor rejected");
        check(store.settings().revision() == revision, "invalid time does not increment revision");
    }

    private static void codec() {
        String defaults = SkyboxConfigPayload.encodeSettings(SkyboxSettings.DEFAULT);
        check(SkyboxConfigPayload.encodeSettings(SkyboxConfigPayload.decodeSettings(defaults)).equals(defaults), "default wire round trip");
        Map<String, String> overrides = new LinkedHashMap<>();
        for (var attribute : SkyEnvironment.attributes()) {
            String value = switch (attribute.kind()) {
                case RGB -> "#123456";
                case ARGB -> "#80112233";
                case FLAG -> "true";
                case PHASE -> "third_quarter";
                case NUMBER -> attribute.positive() ? "1" : attribute.id().equals("visual/star_brightness") ? "0.5" : "0";
            };
            overrides.put(SkyEnvironment.key(attribute), value);
        }
        SkyboxSettings original = new SkyboxSettings(SkyEnvironment.fromEntries(overrides), false, -128, 32, 128,
                true, Set.of("minecraft:the_nether"), Set.of("minecraft:overworld"),
                new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING, 1234), 1000, 42);
        String encoded = SkyboxConfigPayload.encodeSettings(original);
        SkyboxSettings decoded = SkyboxConfigPayload.decodeSettings(encoded);
        check(decoded.environment().describe().size() == 15, "all 15 attributes survive wire");
        check(SkyboxConfigPayload.encodeSettings(decoded).equals(encoded), "full deterministic round trip");
        check(decoded.revision() == 42 && decoded.clockAnchor() == 1000, "revision and shared anchor survive wire");
        check(decoded.environment().color("cloud_color", 0) == (int) 0x80112233L, "ARGB sign and alpha preserved");
        String[] invalid = {
                defaults + "clouds=false\n", defaults + "unexpected=1\n", defaults.replace("protocol=1\n", ""),
                defaults.replace("protocol=1", "protocol=2"), defaults.replace("clouds=true", "clouds=yes"),
                defaults.replace("vanillaCloudHeight=192.0", "vanillaCloudHeight=NaN"),
                defaults.replace("vanillaCloudHeight=192.0", "vanillaCloudHeight=32769"),
                defaults.replace("vanillaCloudFadeStart=128.0", "vanillaCloudFadeStart=384"),
                defaults.replace("skyTimeAnchor=0", "skyTimeAnchor=-1"), defaults.replace("revision=0", "revision=-1"),
                defaults.replace("skyTimeTicks=0", "skyTimeTicks=24000"), defaults.replace("skyTimeMode=auto", "skyTimeMode=future"),
                defaults.replace("localTimeDimensions=", "localTimeDimensions=Minecraft:Bad"),
                defaults + "environment.sky_color=#112233\n", defaults + "environment.visual/unknown=1\n",
                defaults + "environment.visual/cloud_height=Infinity\n", defaults + "\u0000",
                "x".repeat(SkyboxConfigPayload.MAX_PAYLOAD_CHARS + 1)
        };
        for (String bad : invalid) throwsType(IllegalArgumentException.class, () -> SkyboxConfigPayload.decodeSettings(bad), "invalid bounded wire rejected");
        throwsType(IllegalArgumentException.class, () -> SkyboxConfigPayload.decodeSettings("汉".repeat(12000)), "UTF8 byte limit enforced independently of char count");
    }

    private static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }
    private static void write(Path file, Properties properties) throws IOException {
        try (var output = Files.newOutputStream(file)) { properties.store(output, "isolated synthetic test"); }
    }
    private static void restoreJvm(String key, String previous) {
        if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1E-6, message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void throwsType(Class<? extends Throwable> expected, Throwing action, String message) {
        checks++;
        try { action.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(message + ": wrong exception " + error, error);
        }
        throw new AssertionError(message + ": exception not thrown");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}