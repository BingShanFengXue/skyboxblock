package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent, Minecraft-free regression for the environment-attribute registry and its
 * configuration mapping: ids/aliases, typed parsing, strict positivity, snapshot semantics, and the
 * {@code environment.*} ⇄ snapshot round-trip (auto entries, unknown keys, invalid values).
 *
 * <p>Deliberately touches no Minecraft class: parsing and the snapshot are pure JDK, so this runs as
 * a plain {@code main} without a client. The command tree is exercised separately by
 * {@link SkyCommandRegression}, which needs Brigadier/component classes on the classpath.
 *
 * <p>Usage: {@code java dev.yg.skyboxblock.client.SkyEnvironmentRegression [report.txt]}
 */
public final class SkyEnvironmentRegression {
    private static int checks;

    private static void require(boolean pass, String text) {
        checks++;
        if (!pass) throw new AssertionError(text);
    }
    private static void equal(Object expected, Object actual, String text) {
        require(java.util.Objects.equals(expected, actual), text + ": expected " + expected + " but was " + actual);
    }
    private static void near(float expected, float actual, float epsilon, String text) {
        require(Math.abs(expected - actual) <= epsilon, text + ": expected " + expected + " but was " + actual);
    }
    private static void rejects(SkyEnvironment.Attribute attribute, String raw, String text) {
        try {
            SkyEnvironment.parse(attribute, raw);
            require(false, text + " should have been rejected: " + raw);
        } catch (SkyEnvironment.InvalidValueException expected) {
            checks++;
            require(expected.getMessage() != null && !expected.getMessage().isEmpty(),
                    text + " rejection must carry a message");
        }
    }
    private static void accepts(SkyEnvironment.Attribute attribute, String raw, String canonical, String text) {
        equal(canonical, SkyEnvironment.parse(attribute, raw).text(), text);
    }

    public static void main(String[] args) throws Exception {
        registryRegression();
        parsingRegression();
        snapshotRegression();
        configurationRegression();
        System.out.println("SkyEnvironmentRegression: " + checks + " checks OK");
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "SkyEnvironmentRegression: " + checks + " checks OK\n", StandardCharsets.UTF_8);
        }
    }

    // ── registry ────────────────────────────────────────────────────────────────────────────

    private static void registryRegression() {
        List<SkyEnvironment.Attribute> attributes = SkyEnvironment.attributes();
        List<String> expected = List.of(
                "visual/sky_color", "visual/fog_color", "visual/fog_start_distance",
                "visual/fog_end_distance", "visual/sky_fog_end_distance", "visual/cloud_color",
                "visual/cloud_height", "visual/cloud_fog_end_distance", "visual/sunrise_sunset_color",
                "visual/sun_angle", "visual/moon_angle", "visual/star_angle", "visual/moon_phase",
                "visual/star_brightness", "visual/has_sky_occluder");
        equal(expected.size(), attributes.size(), "exactly the fifteen documented attributes");
        for (int i = 0; i < expected.size(); i++) {
            equal(expected.get(i), attributes.get(i).id(), "registration order " + i);
            require(SkyEnvironment.attribute(expected.get(i)) != null, "registered " + expected.get(i));
            require(SkyEnvironment.attribute(expected.get(i)).doc() != null, "documented " + expected.get(i));
        }
        // Every documented route resolves to the same canonical id.
        for (String form : new String[]{"sky_color", "visual/sky_color", "minecraft:visual/sky_color"})
            equal("visual/sky_color", SkyEnvironment.canonical(form), "alias " + form);
        equal("visual/has_sky_occluder", SkyEnvironment.canonical("has_sky_occluder"), "short occluder id");
        equal("visual/cloud_height", SkyEnvironment.canonical("cloud_height"), "short cloud height id");
        equal("visual/fog_end_distance", SkyEnvironment.canonical("minecraft:visual/fog_end_distance"),
                "namespaced full id");
        for (SkyEnvironment.Attribute attribute : attributes) {
            equal(attribute.id(), SkyEnvironment.canonical(attribute.shortId()), "short id alias");
            equal(attribute.id(), SkyEnvironment.canonical(SkyEnvironment.NAMESPACE + attribute.id()),
                    "namespaced alias");
        }
        require(SkyEnvironment.canonical("visual/sky_light_color") == null,
                "sky_light_color is not part of the client sky canvas");
        require(SkyEnvironment.canonical("visual/water_fog_end_distance") == null,
                "underwater fog stays out of the sky canvas");
        require(SkyEnvironment.canonical("nonsense") == null, "unknown ids do not resolve");
        require(SkyEnvironment.canonical("") == null && SkyEnvironment.canonical(null) == null,
                "empty and null ids do not resolve");

        // Distances are strictly positive; cloud fog end keeps its 4096 performance bound.
        for (String id : new String[]{"visual/fog_end_distance", "visual/sky_fog_end_distance",
                "visual/cloud_fog_end_distance"})
            require(SkyEnvironment.attribute(id).positive(), id + " is strictly positive");
        equal(32768F, SkyEnvironment.attribute("visual/fog_end_distance").max(), "fog end maximum");
        equal(32768F, SkyEnvironment.attribute("visual/sky_fog_end_distance").max(), "sky fog end maximum");
        equal(4096F, SkyEnvironment.attribute("visual/cloud_fog_end_distance").max(),
                "cloud fog end keeps the 4096 bound");
        require(!SkyEnvironment.attribute("visual/fog_start_distance").positive(), "fog start may be negative");
        require(SkyEnvironment.attribute("visual/fog_start_distance").min() < 0, "fog start minimum is negative");
        equal(192.33F, SkyEnvironment.attribute("visual/cloud_height").defaultValue(),
                "wiki cloud height default (absolute world Y)");
        equal("environment.visual/sky_color", SkyEnvironment.key(SkyEnvironment.SKY_COLOR), "config key prefix");
        equal("skyboxblock.environment.", SkyEnvironment.JVM_PREFIX, "JVM property prefix");
        equal("auto", SkyEnvironment.AUTO, "auto literal");

        List<String> suggestions = SkyEnvironment.suggestions("visual/cloud");
        require(suggestions.contains("visual/cloud_color") && suggestions.contains("visual/cloud_height")
                && suggestions.contains("visual/cloud_fog_end_distance"), "prefix suggestions: " + suggestions);
        require(SkyEnvironment.suggestions("minecraft:visual/moon").contains("minecraft:visual/moon_phase"),
                "namespace suggestions");
        equal(15, SkyEnvironment.shortIds().size(), "one short id per attribute");
    }

    // ── typed parsing ───────────────────────────────────────────────────────────────────────

    private static void parsingRegression() {
        SkyEnvironment.Attribute sky = SkyEnvironment.SKY_COLOR;
        accepts(sky, "#7ba4ff", "#7BA4FF", "lower-case RGB is canonicalised");
        rejects(sky, "0x7BA4FF", "#-only colour form rejects the 0x prefix");
        rejects(sky, "0X7BA4FF", "#-only colour form rejects upper-case 0X");
        accepts(sky, "  #7BA4FF  ", "#7BA4FF", "surrounding blanks are trimmed");
        rejects(sky, "#7BA4F", "short RGB");
        rejects(sky, "#7BA4FF00", "RGB must not carry alpha");
        rejects(sky, "7BA4FF", "RGB needs a prefix");
        rejects(sky, "#GGGGGG", "non-hex characters");
        rejects(sky, "#7BA 4FF", "embedded whitespace");

        SkyEnvironment.Attribute cloud = SkyEnvironment.CLOUD_COLOR;
        accepts(cloud, "#FFB0D8FF", "#FFB0D8FF", "ARGB keeps alpha");
        accepts(cloud, "#00000000", "#00000000", "fully transparent cloud colour");
        rejects(cloud, "0x00000000", "ARGB also rejects the 0x prefix");
        equal(0x00000000, SkyEnvironment.parse(cloud, "#00000000").color(), "transparent colour bits");
        equal(0xFFB0D8FF, SkyEnvironment.parse(cloud, "#FFB0D8FF").color(), "opaque colour bits");
        // Signed int bits must survive verbatim (alpha >= 0x80 makes the int negative).
        equal(-1, SkyEnvironment.parse(cloud, "#FFFFFFFF").color(), "white stays -1 as a signed int");
        equal(0x80FFFFFF, SkyEnvironment.parse(cloud, "#80FFFFFF").color(), "high alpha preserved");
        equal(0xFF000000, SkyEnvironment.parse(cloud, "#FF000000").color(), "opaque black preserved");
        rejects(cloud, "#FFB0D8", "ARGB needs eight digits");
        rejects(cloud, "#FFFFFFFFF", "ARGB rejects nine digits");

        SkyEnvironment.Attribute fogEnd = SkyEnvironment.FOG_END_DISTANCE;
        accepts(fogEnd, "1024", "1024", "plain distance");
        accepts(fogEnd, "96.5", "96.5", "fractional distance");
        accepts(fogEnd, "32768", "32768", "distance at the maximum");
        rejects(fogEnd, "0", "distance 0 is rejected");
        rejects(fogEnd, "-1", "negative distance is rejected");
        rejects(fogEnd, "-0.0", "negative zero is rejected");
        rejects(fogEnd, "NaN", "NaN distance is rejected");
        rejects(fogEnd, "-Infinity", "Infinity distance is rejected");
        rejects(fogEnd, "32769", "distance above the maximum");
        rejects(fogEnd, "1e40", "overflowing distance");
        accepts(SkyEnvironment.attribute("visual/cloud_fog_end_distance"), "4096", "4096", "cloud fog upper bound");
        rejects(SkyEnvironment.attribute("visual/cloud_fog_end_distance"), "4097", "cloud fog beyond 4096");
        rejects(SkyEnvironment.attribute("visual/cloud_fog_end_distance"), "0", "cloud fog zero rejected");
        rejects(SkyEnvironment.SKY_FOG_END_DISTANCE, "0", "sky fog zero rejected");

        SkyEnvironment.Attribute start = SkyEnvironment.FOG_START_DISTANCE;
        accepts(start, "-64", "-64", "negative fog start");
        accepts(start, "0", "0", "zero fog start");
        accepts(start, "2048.5", "2048.5", "fractional fog start");
        rejects(start, "nonsense", "non-numeric fog start");

        SkyEnvironment.Attribute angle = SkyEnvironment.MOON_ANGLE;
        accepts(angle, "180", "180", "moon angle is a plain angle, 0 = straight up");
        accepts(angle, "-1000.25", "-1000.25", "angles are not wrapped");
        accepts(angle, "540", "540", "angles above a full turn stay as typed");
        rejects(angle, "Infinity", "infinite angle");
        accepts(SkyEnvironment.SUN_ANGLE, "-45", "-45", "sun angle may be negative");
        accepts(SkyEnvironment.STAR_ANGLE, "1234.5", "1234.5", "star angle");

        SkyEnvironment.Attribute brightness = SkyEnvironment.STAR_BRIGHTNESS;
        accepts(brightness, "0", "0", "zero brightness");
        accepts(brightness, "1", "1", "full brightness");
        rejects(brightness, "1.01", "brightness above 1");
        rejects(brightness, "-0.01", "brightness below 0");

        SkyEnvironment.Attribute occluder = SkyEnvironment.HAS_SKY_OCCLUDER;
        accepts(occluder, "true", "true", "boolean true");
        accepts(occluder, "FALSE", "false", "boolean is canonicalised");
        rejects(occluder, "1", "numeric boolean is rejected");
        rejects(occluder, "yes", "word boolean is rejected");

        SkyEnvironment.Attribute phase = SkyEnvironment.MOON_PHASE;
        List<String> names = List.of("full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
                "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous");
        equal(names, SkyEnvironment.MOON_PHASES, "vanilla phase order, 0 = full moon");
        for (int i = 0; i < names.size(); i++) {
            accepts(phase, names.get(i), names.get(i), "phase name " + names.get(i));
            accepts(phase, Integer.toString(i), names.get(i), "phase index " + i);
            equal(i, SkyEnvironment.parse(phase, names.get(i)).moonPhase(), "phase ordinal " + i);
        }
        accepts(phase, "WAXING_GIBBOUS", "waxing_gibbous", "phase names are case-insensitive");
        rejects(phase, "8", "phase index above 7");
        rejects(phase, "-1", "negative phase index");
        rejects(phase, "harvest_moon", "unknown phase name");

        accepts(SkyEnvironment.CLOUD_HEIGHT, "192.33", "192.33", "wiki cloud height value");
        accepts(SkyEnvironment.CLOUD_HEIGHT, "-64", "-64", "cloud height below sea level");
        rejects(SkyEnvironment.CLOUD_HEIGHT, "40000", "cloud height above the supported range");
        rejects(SkyEnvironment.CLOUD_HEIGHT, "", "empty value");
        rejects(sky, null, "null value");
        rejects(null, "1", "unknown attribute");
    }

    // ── snapshot ────────────────────────────────────────────────────────────────────────────

    private static void snapshotRegression() {
        SkyEnvironment empty = SkyEnvironment.EMPTY;
        require(!empty.has("visual/sky_color"), "empty snapshot has no overrides");
        equal(0x12345678, empty.color("visual/sky_color", 0x12345678), "empty colour returns fallback");
        near(7.5F, empty.number("visual/fog_end_distance", 7.5F), 0F, "empty number returns fallback");
        require(empty.flag("visual/has_sky_occluder", true), "empty flag returns fallback");
        equal(3, empty.moonPhase(3), "empty phase returns fallback");
        equal("auto", empty.effective("visual/moon_angle"), "empty effective text is auto");
        require(empty.overrides().isEmpty(), "empty snapshot has no entries");
        require(!empty.overridden("visual/sky_color"), "empty snapshot overrides nothing");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("environment.visual/sky_color", "#7BA4FF");
        entries.put("environment.visual/cloud_color", "#C8FFD0F0");
        entries.put("environment.visual/fog_end_distance", "3072");
        entries.put("environment.visual/fog_start_distance", "-128");
        entries.put("environment.visual/star_brightness", "0.25");
        entries.put("environment.visual/moon_phase", "waxing_gibbous");
        entries.put("environment.visual/has_sky_occluder", "true");
        entries.put("environment.visual/moon_angle", "180");
        entries.put("environment.foo", "1");
        entries.put("environment.visual/fog_color", "not a colour");
        entries.put("environment.visual/sky_fog_end_distance", "auto");
        entries.put("clouds", "true");
        Map<String, String> copy = new LinkedHashMap<>(entries);
        List<String> warnings = new ArrayList<>();
        SkyEnvironment snapshot = SkyEnvironment.fromEntries(entries, warnings::add);
        require(snapshot.has("visual/sky_color"), "sky colour is overridden");
        equal(0xFF7BA4FF, snapshot.color("visual/sky_color", 0), "RGB colour reads back opaque");
        equal(0xC8FFD0F0, snapshot.color("visual/cloud_color", 0), "ARGB colour keeps alpha");
        near(3072F, snapshot.number("visual/fog_end_distance", 1F), 0F, "distance override");
        near(-128F, snapshot.number("visual/fog_start_distance", 1F), 0F, "negative fog start override");
        near(0.25F, snapshot.number("visual/star_brightness", 1F), 0F, "brightness override");
        require(snapshot.flag("visual/has_sky_occluder", false), "flag override");
        equal(7, snapshot.moonPhase(-1), "moon phase override");
        equal("waxing_gibbous", snapshot.effective("visual/moon_phase"), "moon phase text");
        near(180F, snapshot.number("visual/moon_angle", 0F), 0F, "angle override");
        require(!snapshot.has("visual/fog_color"), "invalid entry drops to auto");
        require(!snapshot.has("visual/sky_fog_end_distance"), "auto text is a missing override");
        require(!snapshot.has("foo") && !snapshot.has("clouds"), "non-environment keys are ignored");
        equal(2, warnings.size(), "unknown attribute and invalid value both warn: " + warnings);
        equal(copy, entries, "input map untouched");
        equal(snapshot.color("sky_color", 0), snapshot.color("minecraft:visual/sky_color", 0),
                "alias lookup on a snapshot");
        // Wrong-kind queries fail loudly instead of returning a meaningless fallback.
        rejectsKind(() -> snapshot.number("visual/sky_color", 0), "number() on a colour");
        rejectsKind(() -> snapshot.color("visual/fog_end_distance", 0), "color() on a number");
        rejectsKind(() -> snapshot.flag("visual/fog_end_distance", false), "flag() on a number");
        // Overrides keep file order and are immutable.
        require(snapshot.overrides().keySet().iterator().next().equals("environment.visual/sky_color"),
                "override order follows insertion");
        require(snapshot.overrides().size() == 8, "eight valid overrides: " + snapshot.overrides());
        try {
            snapshot.overrides().put("environment.visual/sky_color", "#000000");
            require(false, "overrides map is immutable");
        } catch (UnsupportedOperationException expected) { checks++; }
        // A snapshot is a value: rebuilding from the same entries gives the same answers.
        SkyEnvironment again = SkyEnvironment.fromEntries(copy);
        equal(snapshot.overrides(), again.overrides(), "rebuilt snapshot is identical");
        // Every documented query form works against a full snapshot.
        for (SkyEnvironment.Attribute attribute : SkyEnvironment.attributes()) {
            require(snapshot.effective(attribute.id()) != null, "effective text for " + attribute.id());
        }
    }

    private static void rejectsKind(Runnable action, String text) {
        try {
            action.run();
            require(false, text + " should throw");
        } catch (SkyEnvironment.InvalidValueException expected) { checks++; }
    }

    // ── configuration mapping (pure JDK, no SkyboxConfig / FabricLoader) ────────────────────

    private static void configurationRegression() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("environment.visual/sky_color", "auto");
        entries.put("environment.visual/cloud_height", "#not-a-number");
        entries.put("environment.visual/moon_phase", "new_moon");
        entries.put("environment.visual/fog_end_distance", "0");
        SkyEnvironment snapshot = SkyEnvironment.fromEntries(entries);
        require(!snapshot.has("visual/sky_color"), "auto entry is the same as absent");
        require(!snapshot.has("visual/cloud_height"), "invalid file value falls back to auto");
        require(!snapshot.has("visual/fog_end_distance"), "zero distance in the file falls back to auto");
        equal("new_moon", snapshot.effective("visual/moon_phase"), "valid file value survives");

        // Simulated round-trip: snapshot -> file text -> snapshot.
        Map<String, String> written = new LinkedHashMap<>(snapshot.overrides());
        written.put("environment.visual/star_brightness", SkyEnvironment.parse(
                SkyEnvironment.STAR_BRIGHTNESS, "0.75").text());
        SkyEnvironment reloaded = SkyEnvironment.fromEntries(written);
        equal(snapshot.effective("visual/moon_phase"), reloaded.effective("visual/moon_phase"),
                "phase survives a round-trip");
        near(0.75F, reloaded.number("visual/star_brightness", 0F), 0F, "brightness survives a round-trip");
        equal(2, reloaded.overrides().size(), "only overrides are written: " + reloaded.overrides());
        // Nothing to write means an empty, auto-only property file.
        require(SkyEnvironment.fromEntries(Map.of()).overrides().isEmpty(), "no overrides, no keys");

        aliasRegression();
    }

    /** Aliased keys collapse deterministically; a stale alias can never win or resurrect. */
    private static void aliasRegression() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("environment.sky_color", "#000001");
        aliases.put("environment.minecraft:visual/sky_color", "#000002");
        aliases.put("environment.visual/sky_color", "#000003");
        Map<String, String> canonical = SkyEnvironment.canonicalEntries(aliases);
        equal(java.util.Set.of("visual/sky_color"), canonical.keySet(), "aliases collapse to one entry");
        equal("#000003", canonical.get("visual/sky_color"), "the canonical key wins over aliases");
        equal("#000003", SkyEnvironment.fromEntries(aliases).effective("visual/sky_color"),
                "snapshot uses the canonical alias");

        Map<String, String> namespaced = new LinkedHashMap<>();
        namespaced.put("environment.sky_color", "#000001");
        namespaced.put("environment.minecraft:visual/sky_color", "#000002");
        equal("#000002", SkyEnvironment.canonicalEntries(namespaced).get("visual/sky_color"),
                "minecraft: form beats the short form when the canonical key is absent");

        Map<String, String> shorthand = new LinkedHashMap<>();
        shorthand.put("environment.sky_color", "#000001");
        equal("#000001", SkyEnvironment.fromEntries(shorthand).effective("visual/sky_color"),
                "a short-id key still loads");

        // An alias with a later-wins order does not depend on map iteration order.
        Map<String, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put("environment.visual/sky_color", "#000003");
        reversed.put("environment.sky_color", "#000001");
        equal("#000003", SkyEnvironment.fromEntries(reversed).effective("visual/sky_color"),
                "canonical wins regardless of entry order");

        // Unknown keys survive canonicalisation so they can be warned about and preserved.
        Map<String, String> mixed = new LinkedHashMap<>(aliasRegressionBase());
        Map<String, String> collapsed = SkyEnvironment.canonicalEntries(mixed);
        require(collapsed.containsKey("environment.bogus"), "unknown environment keys survive canonicalisation");
        equal("keep", collapsed.get("environment.bogus"), "unknown value untouched");
        // reset (alias removal) must find every alias of one attribute.
        List<String> found = SkyEnvironment.aliasedKeys(mixed.keySet(),
                SkyEnvironment.attribute("visual/sky_color"));
        require(found.contains("environment.sky_color") && found.contains("environment.visual/sky_color")
                        && found.contains("environment.minecraft:visual/sky_color"),
                "every alias is found for reset: " + found);
        require(!found.contains("environment.bogus"), "unknown keys are not treated as aliases");
    }

    private static Map<String, String> aliasRegressionBase() {
        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put("environment.sky_color", "#000001");
        mixed.put("environment.visual/sky_color", "#000003");
        mixed.put("environment.minecraft:visual/sky_color", "#000002");
        mixed.put("environment.bogus", "keep");
        return mixed;
    }
}
