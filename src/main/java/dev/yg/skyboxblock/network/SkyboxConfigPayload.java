package dev.yg.skyboxblock.network;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import dev.yg.skyboxblock.SkyboxSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Versioned, bounded, server-to-client ONLY complete settings snapshot. */
public record SkyboxConfigPayload(SkyboxSettings settings) implements CustomPacketPayload {
    public static final int MAX_PAYLOAD_CHARS = 32768;
    public static final int MAX_PAYLOAD_BYTES = 32768;
    public static final Type<SkyboxConfigPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("skyboxblock", "settings_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkyboxConfigPayload> CODEC = new StreamCodec<>() {
        @Override public SkyboxConfigPayload decode(RegistryFriendlyByteBuf buffer) {
            return new SkyboxConfigPayload(decodeSettings(buffer.readUtf(MAX_PAYLOAD_CHARS)));
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, SkyboxConfigPayload payload) {
            buffer.writeUtf(encodeSettings(payload.settings()), MAX_PAYLOAD_CHARS);
        }
    };
    private static final Set<String> REQUIRED = Set.of("protocol", "revision", "clouds", "vanillaCloudHeight",
            "vanillaCloudFadeStart", "vanillaCloudFadeEnd", "simulatedWeather", "localTimeDimensions",
            "simulatedTimeDimensions", "skyTimeMode", "skyTimeTicks", "skyTimeAnchor");

    public SkyboxConfigPayload { Objects.requireNonNull(settings, "settings"); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Pure encoding path, also exercised by the classpath-only backend regression. */
    public static String encodeSettings(SkyboxSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("protocol", "1");
        entries.put("revision", Long.toString(settings.revision()));
        entries.put("clouds", Boolean.toString(settings.clouds()));
        entries.put("vanillaCloudHeight", Float.toString(settings.cloudHeight()));
        entries.put("vanillaCloudFadeStart", Float.toString(settings.cloudFadeStart()));
        entries.put("vanillaCloudFadeEnd", Float.toString(settings.cloudFadeEnd()));
        entries.put("simulatedWeather", settings.simulatedLocalWeather() ? "local" : "clear");
        entries.put("localTimeDimensions", String.join(",", new TreeSet<>(settings.localTimeDimensions())));
        entries.put("simulatedTimeDimensions", String.join(",", new TreeSet<>(settings.simulatedTimeDimensions())));
        entries.put("skyTimeMode", settings.clock().mode().id());
        entries.put("skyTimeTicks", Integer.toString(settings.clock().ticks()));
        entries.put("skyTimeAnchor", Long.toString(settings.clockAnchor()));
        entries.putAll(settings.environment().overrides());
        StringBuilder output = new StringBuilder();
        entries.forEach((key, value) -> {
            if (key.indexOf('\n') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                throw new IllegalArgumentException("Settings contain a line separator");
            output.append(key).append('=').append(value).append('\n');
        });
        String wire = output.toString();
        checkLength(wire);
        return wire;
    }

    /** Reject unknown fields, duplicate fields, bad IDs, malformed/non-finite values and oversized data. */
    public static SkyboxSettings decodeSettings(String wire) {
        Objects.requireNonNull(wire, "wire");
        checkLength(wire);
        if (wire.indexOf('\r') >= 0 || wire.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid settings separators");
        Map<String, String> entries = new LinkedHashMap<>();
        String[] lines = wire.split("\n", -1);
        if (lines.length > 64) throw new IllegalArgumentException("Too many settings fields");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Malformed settings field");
            String key = line.substring(0, equals), value = line.substring(equals + 1);
            if (entries.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate settings field: " + key);
            if (!REQUIRED.contains(key)) {
                if (!key.startsWith(SkyEnvironment.KEY_PREFIX)) throw new IllegalArgumentException("Unknown settings field: " + key);
                String id = key.substring(SkyEnvironment.KEY_PREFIX.length());
                var attribute = SkyEnvironment.attribute(id);
                if (attribute == null || !attribute.id().equals(id)) throw new IllegalArgumentException("Unknown or aliased environment field: " + key);
                if (!value.equals(SkyEnvironment.AUTO)) SkyEnvironment.parse(attribute, value);
            }
        }
        if (!entries.keySet().containsAll(REQUIRED)) throw new IllegalArgumentException("Incomplete settings snapshot");
        if (!entries.get("protocol").equals("1")) throw new IllegalArgumentException("Unsupported settings protocol");
        String weather = entries.get("simulatedWeather");
        if (!weather.equals("clear") && !weather.equals("local")) throw new IllegalArgumentException("Invalid weather policy");
        String clouds = entries.get("clouds");
        if (!clouds.equals("true") && !clouds.equals("false")) throw new IllegalArgumentException("Invalid clouds flag");
        return new SkyboxSettings(SkyEnvironment.fromEntries(entries), Boolean.parseBoolean(clouds),
                Float.parseFloat(entries.get("vanillaCloudHeight")), Float.parseFloat(entries.get("vanillaCloudFadeStart")),
                Float.parseFloat(entries.get("vanillaCloudFadeEnd")), weather.equals("local"),
                dimensions(entries.get("localTimeDimensions")), dimensions(entries.get("simulatedTimeDimensions")),
                new SkyTimeClock.Setting(SkyTimeClock.Mode.parse(entries.get("skyTimeMode")), Integer.parseInt(entries.get("skyTimeTicks"))),
                Long.parseLong(entries.get("skyTimeAnchor")), Long.parseLong(entries.get("revision")));
    }

    private static Set<String> dimensions(String text) {
        if (text.isEmpty()) return Set.of();
        String[] values = text.split(",", -1);
        if (values.length > 32 || Arrays.stream(values).anyMatch(String::isEmpty))
            throw new IllegalArgumentException("Invalid dimension policy list");
        Set<String> result = Arrays.stream(values).collect(Collectors.toUnmodifiableSet());
        if (result.size() != values.length) throw new IllegalArgumentException("Duplicate dimension policy");
        return result;
    }

    private static void checkLength(String wire) {
        if (wire.length() > MAX_PAYLOAD_CHARS || wire.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Settings payload exceeds 32768 bytes/chars");
    }
}