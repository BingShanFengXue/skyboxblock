package dev.yg.skyboxblock;

import java.util.Objects;
import java.util.Set;

/** Immutable server-authoritative sky settings. Safe to load without Minecraft client classes. */
public record SkyboxSettings(SkyEnvironment environment, boolean clouds, float cloudHeight,
                             float cloudFadeStart, float cloudFadeEnd, boolean simulatedLocalWeather,
                             Set<String> localTimeDimensions, Set<String> simulatedTimeDimensions,
                             SkyTimeClock.Setting clock, long clockAnchor, long revision) {
    public static final SkyboxSettings DEFAULT = new SkyboxSettings(SkyEnvironment.EMPTY, true, 192F,
            128F, 384F, false, Set.of(), Set.of(), SkyTimeClock.Setting.AUTO, 0L, 0L);

    public SkyboxSettings {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(clock, "clock");
        localTimeDimensions = Set.copyOf(Objects.requireNonNull(localTimeDimensions, "localTimeDimensions"));
        simulatedTimeDimensions = Set.copyOf(Objects.requireNonNull(simulatedTimeDimensions, "simulatedTimeDimensions"));
        if (!Float.isFinite(cloudHeight) || cloudHeight < -32768F || cloudHeight > 32768F)
            throw new IllegalArgumentException("Cloud height must be finite in -32768..32768");
        if (!Float.isFinite(cloudFadeStart) || !Float.isFinite(cloudFadeEnd)
                || cloudFadeStart < 0F || cloudFadeStart >= cloudFadeEnd || cloudFadeEnd < 16F || cloudFadeEnd > 4096F)
            throw new IllegalArgumentException("Cloud fade requires finite 0 <= start < end, end in 16..4096");
        if (clockAnchor < 0L || revision < 0L)
            throw new IllegalArgumentException("Clock anchor and revision must be non-negative");
        if (localTimeDimensions.size() > 32 || simulatedTimeDimensions.size() > 32)
            throw new IllegalArgumentException("Dimension policy lists support at most 32 entries each");
        for (String dimension : localTimeDimensions) validateDimension(dimension);
        for (String dimension : simulatedTimeDimensions) validateDimension(dimension);
    }

    private static void validateDimension(String dimension) {
        if (dimension.isBlank() || dimension.length() > 256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid dimension id: " + dimension);
    }

    public boolean usesLocalTime(String dimension, boolean normalSky) {
        if (simulatedTimeDimensions.contains(dimension)) return false;
        return localTimeDimensions.contains(dimension) || normalSky;
    }

    /** Uses the shared server overworld gameTime, never wall-clock/offline elapsed time. */
    public double clockTicks(long gameTick, float partial, double automaticTicks) {
        return switch (clock.mode()) {
            case AUTO -> automaticTicks;
            case LOCKED -> clock.ticks();
            case RUNNING -> {
                long delta = gameTick >= clockAnchor ? gameTick - clockAnchor : 0L;
                double fraction = Float.isFinite(partial) ? Math.max(0F, Math.min(1F, partial)) : 0.0;
                yield SkyTimeClock.normalize(clock.ticks() + Math.floorMod(delta, (long) SkyTimeClock.DAY) + fraction);
            }
        };
    }
}