package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

/** Only independent simulation options remain; old capture/panorama/style switches are removed. */
public final class SkyboxConfig {
    private static SkyboxConfig instance;
    private final boolean clouds, debug, simulatedLocalWeather;
    private float vanillaCloudHeight = SkyRenderMath.VANILLA_CLOUD_HEIGHT;
    public static final float MIN_CLOUD_HEIGHT=-32768F, MAX_CLOUD_HEIGHT=32768F;
    public static final float DEFAULT_CLOUD_FADE_START=128F, DEFAULT_CLOUD_FADE_END=384F;
    public static final float MIN_CLOUD_DISTANCE=16F, MAX_CLOUD_DISTANCE=4096F;
    private float vanillaCloudFadeStart=DEFAULT_CLOUD_FADE_START, vanillaCloudFadeEnd=DEFAULT_CLOUD_FADE_END;
    private final java.util.Set<String> localTimeDimensions, simulatedTimeDimensions;
    private SkyboxConfig(boolean clouds,boolean debug,boolean simulatedLocalWeather,
                         java.util.Set<String> localTimeDimensions,java.util.Set<String> simulatedTimeDimensions) {
        this.clouds=clouds; this.debug=debug; this.simulatedLocalWeather=simulatedLocalWeather;
        this.localTimeDimensions=localTimeDimensions; this.simulatedTimeDimensions=simulatedTimeDimensions;
    }
    public static SkyboxConfig get() {
        if (instance == null) {
            Properties properties = new Properties();
            var file = FabricLoader.getInstance().getConfigDir().resolve("skyboxblock.properties");
            if (Files.exists(file)) {
                try (var stream = Files.newInputStream(file)) { properties.load(stream); }
                catch (IOException exception) { SkyboxBlockMod.LOGGER.warn("[skyboxblock] config read failed", exception); }
            }
            instance = fromProperties(properties);
        }
        return instance;
    }
    static SkyboxConfig fromProperties(Properties file) {
        SkyboxConfig config=new SkyboxConfig(Boolean.parseBoolean(option(file,"clouds","true")),
                Boolean.parseBoolean(option(file,"debug","false")),
                option(file,"simulatedWeather","clear").equalsIgnoreCase("local"),
                dimensions(option(file,"localTimeDimensions","")),dimensions(option(file,"simulatedTimeDimensions","")));
        config.vanillaCloudHeight=parseCloudHeight(option(file,"vanillaCloudHeight","192"));
        config.vanillaCloudFadeEnd=parseCloudDistance(option(file,"vanillaCloudFadeEnd","384"));
        config.vanillaCloudFadeStart=parseCloudFadeStart(option(file,"vanillaCloudFadeStart","128"),config.vanillaCloudFadeEnd);
        return config;
    }
    private static String option(Properties file,String key,String fallback) {
        return System.getProperty("skyboxblock."+key,file.getProperty(key,fallback));
    }
    private static java.util.Set<String> dimensions(String text) {
        return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public boolean usesLocalTime(String dimension,boolean normalSky) {
        if (simulatedTimeDimensions.contains(dimension)) return false;
        return localTimeDimensions.contains(dimension) || normalSky;
    }
    static float parseCloudHeight(String text) {
        try {
            float value=Float.parseFloat(text);
            if (Float.isFinite(value) && value>=MIN_CLOUD_HEIGHT && value<=MAX_CLOUD_HEIGHT) return value;
        } catch (NumberFormatException ignored) { }
        SkyboxBlockMod.LOGGER.warn("[skyboxblock] invalid vanillaCloudHeight {}; using 192",text);
        return SkyRenderMath.VANILLA_CLOUD_HEIGHT;
    }
    static float parseCloudDistance(String text) {
        try {
            float value=Float.parseFloat(text);
            if(Float.isFinite(value) && value>=MIN_CLOUD_DISTANCE && value<=MAX_CLOUD_DISTANCE) return value;
        } catch(NumberFormatException ignored) { }
        SkyboxBlockMod.LOGGER.warn("[skyboxblock] invalid vanillaCloudFadeEnd {}; using 384",text);
        return DEFAULT_CLOUD_FADE_END;
    }
    static float parseCloudFadeStart(String text,float end) {
        try {
            float value=Float.parseFloat(text);
            if(Float.isFinite(value) && value>=0 && value<end) return value;
        } catch(NumberFormatException ignored) { }
        SkyboxBlockMod.LOGGER.warn("[skyboxblock] invalid vanillaCloudFadeStart {} for end {}; using end/3",text,end);
        return end/3F;
    }
    public float vanillaCloudFadeStart() { return vanillaCloudFadeStart; }
    public float vanillaCloudFadeEnd() { return vanillaCloudFadeEnd; }
    public float vanillaCloudHeight() { return vanillaCloudHeight; }
    public static void reload() { instance=null; get(); SkyVanillaClouds.invalidate(); }
    public static void setVanillaCloudHeight(float height) throws IOException {
        if (!Float.isFinite(height) || height<MIN_CLOUD_HEIGHT || height>MAX_CLOUD_HEIGHT)
            throw new IllegalArgumentException("Cloud height outside finite supported range");
        update(properties -> properties.setProperty("vanillaCloudHeight",Float.toString(height)));
    }
    public static void setVanillaCloudDistance(float end) throws IOException {
        setVanillaCloudFade(end/3F,end);
    }
    public static void setVanillaCloudFade(float start,float end) throws IOException {
        if(!Float.isFinite(start) || !Float.isFinite(end) || start<0 || start>=end || end<MIN_CLOUD_DISTANCE || end>MAX_CLOUD_DISTANCE)
            throw new IllegalArgumentException("Cloud fade requires finite 0 <= start < end, end in 16..4096 blocks");
        update(properties -> {
            properties.setProperty("vanillaCloudFadeStart",Float.toString(start));
            properties.setProperty("vanillaCloudFadeEnd",Float.toString(end));
        });
    }
    private static void update(java.util.function.Consumer<Properties> mutation) throws IOException {
        var file=FabricLoader.getInstance().getConfigDir().resolve("skyboxblock.properties");
        Properties properties=new Properties();
        if (Files.exists(file)) try (var stream=Files.newInputStream(file)) { properties.load(stream); }
        mutation.accept(properties);
        Files.createDirectories(file.getParent());
        var pending=Files.createTempFile(file.getParent(),"skyboxblock-",".properties.tmp");
        try {
            try (var stream=Files.newOutputStream(pending)) {
                properties.store(stream,"Skybox Block - client-side vanilla sky block options only");
            }
            try { Files.move(pending,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(pending,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(pending); }
        reload();
    }
    public boolean simulatedLocalWeather() { return simulatedLocalWeather; }
    public boolean clouds() { return clouds; }
    public boolean debug() { return debug; }
}
