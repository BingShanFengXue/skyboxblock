package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

/** Only independent simulation options remain; old capture/panorama/style switches are removed. */
public final class SkyboxConfig {
    /**
     * Own logger so config loading never triggers the mod's static initialisation (registries) — the
     * classpath-only regressions exercise this file without a game client.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("skyboxblock");
    private static SkyboxConfig instance;
    private final boolean clouds, debug, simulatedLocalWeather;
    private float vanillaCloudHeight = SkyRenderMath.VANILLA_CLOUD_HEIGHT;
    public static final float MIN_CLOUD_HEIGHT=-32768F, MAX_CLOUD_HEIGHT=32768F;
    public static final float DEFAULT_CLOUD_FADE_START=128F, DEFAULT_CLOUD_FADE_END=384F;
    public static final float MIN_CLOUD_DISTANCE=16F, MAX_CLOUD_DISTANCE=4096F;
    private float vanillaCloudFadeStart=DEFAULT_CLOUD_FADE_START, vanillaCloudFadeEnd=DEFAULT_CLOUD_FADE_END;
    private final java.util.Set<String> localTimeDimensions, simulatedTimeDimensions;
    /** Immutable snapshot of the {@code environment.*} overrides; rebuilt by {@link #reload()}. */
    private SkyEnvironment environment = SkyEnvironment.EMPTY;
    private SkyTimeClock.Setting skyTime=SkyTimeClock.Setting.AUTO;
    private SkyboxConfig(boolean clouds,boolean debug,boolean simulatedLocalWeather,
                         java.util.Set<String> localTimeDimensions,java.util.Set<String> simulatedTimeDimensions) {
        this.clouds=clouds; this.debug=debug; this.simulatedLocalWeather=simulatedLocalWeather;
        this.localTimeDimensions=localTimeDimensions; this.simulatedTimeDimensions=simulatedTimeDimensions;
    }
    /** Test-only override of the config file (null = the real instance config directory). */
    private static java.nio.file.Path testFile;

    /** Points every read/write at {@code file}; used by the Minecraft-free regression. */
    public static void installTestFile(java.nio.file.Path file) { testFile=file; instance=null; }
    /** Restores the real instance config file. */
    public static void clearTestFile() { testFile=null; instance=null; }
    /** Absolute config file currently in use. */
    static java.nio.file.Path configFile() {
        if (testFile!=null) return testFile.toAbsolutePath();
        return FabricLoader.getInstance().getConfigDir().resolve("skyboxblock.properties");
    }
    static String testFileText() {
        try { return Files.readString(configFile(), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }
    public static SkyboxConfig get() {
        if (instance == null) {
            if(SkyboxServerState.enforced()) {
                instance=new SkyboxConfig(true,Boolean.getBoolean("skyboxblock.debug"),false,java.util.Set.of(),java.util.Set.of());
                return instance;
            }
            Properties properties = new Properties();
            var file = configFile();
            if (Files.exists(file)) {
                try (var stream = Files.newInputStream(file)) { properties.load(stream); }
                catch (IOException | IllegalArgumentException exception) {
                    // A malformed Unicode escape makes Properties.load throw IllegalArgumentException
                    // after partially loading; drop everything so no half-read value survives.
                    properties.clear();
                    LOGGER.warn("[skyboxblock] config read failed; using defaults", exception);
                }
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
        config.environment=environmentSnapshot(file);
        try {
            var mode=SkyTimeClock.Mode.parse(option(file,"skyTimeMode","auto"));
            int ticks=Integer.parseInt(option(file,"skyTimeTicks","0").trim());
            config.skyTime=new SkyTimeClock.Setting(mode,ticks);
        } catch(IllegalArgumentException invalid) {
            config.skyTime=SkyTimeClock.Setting.AUTO;
            LOGGER.warn("[skyboxblock] invalid sky clock settings; using auto: {}",invalid.getMessage());
        }
        return config;
    }
    /**
     * Environment overrides: file entries under {@code environment.} plus JVM overrides of the form
     * {@code -Dskyboxblock.environment.visual/sky_color=#RRGGBB}, which win over the file.
     *
     * <p>Both sources are collected as raw key→value maps and collapsed by
     * {@link SkyEnvironment#canonicalEntries} <em>before</em> they meet, so aliases never shadow the
     * canonical key and a JVM alias still beats a file canonical key.
     */
    static SkyEnvironment environmentSnapshot(Properties file) {
        java.util.Map<String,String> fileEntries=new java.util.LinkedHashMap<>();
        for (String name : file.stringPropertyNames()) {
            if (name.startsWith(SkyEnvironment.KEY_PREFIX))
                fileEntries.put(name,file.getProperty(name,""));
        }
        java.util.Map<String,String> jvmEntries=new java.util.LinkedHashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (!name.startsWith(SkyEnvironment.JVM_PREFIX)) continue;
            jvmEntries.put(name.substring("skyboxblock.".length()),System.getProperty(name,""));
        }
        java.util.Map<String,String> merged=new java.util.LinkedHashMap<>();
        if (!jvmEntries.isEmpty()) {
            merged.putAll(SkyEnvironment.canonicalEntries(jvmEntries));
            LOGGER.warn("[skyboxblock] {} 个环境属性由 JVM 参数提供，优先于配置文件", jvmEntries.size());
        }
        java.util.Map<String,String> fileCanonical=SkyEnvironment.canonicalEntries(fileEntries);
        for (var entry : fileCanonical.entrySet()) {
            String previous=merged.putIfAbsent(entry.getKey(),entry.getValue());
            if (previous!=null && !previous.equals(entry.getValue()))
                LOGGER.warn("[skyboxblock] JVM 参数覆盖配置文件中的 environment.{}",entry.getKey());
        }
        // fromEntries consumes on-disk keys; canonicalEntries returns bare IDs for known keys.
        java.util.Map<String,String> rawMerged=new java.util.LinkedHashMap<>();
        merged.forEach((key,value) -> rawMerged.put(key.startsWith(SkyEnvironment.KEY_PREFIX)
                ? key : SkyEnvironment.KEY_PREFIX+key,value));
        return SkyEnvironment.fromEntries(rawMerged,message -> LOGGER.warn("[skyboxblock] {}",message));
    }
    private static String option(Properties file,String key,String fallback) {
        return System.getProperty("skyboxblock."+key,file.getProperty(key,fallback));
    }
    private static java.util.Set<String> dimensions(String text) {
        return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public boolean usesLocalTime(String dimension,boolean normalSky) {
        if(SkyboxServerState.enforced()) return SkyboxServerState.get().usesLocalTime(dimension,normalSky);
        if (simulatedTimeDimensions.contains(dimension)) return false;
        return localTimeDimensions.contains(dimension) || normalSky;
    }
    static float parseCloudHeight(String text) {
        try {
            float value=Float.parseFloat(text);
            if (Float.isFinite(value) && value>=MIN_CLOUD_HEIGHT && value<=MAX_CLOUD_HEIGHT) return value;
        } catch (NumberFormatException ignored) { }
        LOGGER.warn("[skyboxblock] invalid vanillaCloudHeight {}; using 192",text);
        return SkyRenderMath.VANILLA_CLOUD_HEIGHT;
    }
    static float parseCloudDistance(String text) {
        try {
            float value=Float.parseFloat(text);
            if(Float.isFinite(value) && value>=MIN_CLOUD_DISTANCE && value<=MAX_CLOUD_DISTANCE) return value;
        } catch(NumberFormatException ignored) { }
        LOGGER.warn("[skyboxblock] invalid vanillaCloudFadeEnd {}; using 384",text);
        return DEFAULT_CLOUD_FADE_END;
    }
    static float parseCloudFadeStart(String text,float end) {
        try {
            float value=Float.parseFloat(text);
            if(Float.isFinite(value) && value>=0 && value<end) return value;
        } catch(NumberFormatException ignored) { }
        LOGGER.warn("[skyboxblock] invalid vanillaCloudFadeStart {} for end {}; using end/3",text,end);
        return end/3F;
    }
    public float vanillaCloudFadeStart() { return SkyboxServerState.enforced() ? SkyboxServerState.get().cloudFadeStart() : vanillaCloudFadeStart; }
    public float vanillaCloudFadeEnd() { return SkyboxServerState.enforced() ? SkyboxServerState.get().cloudFadeEnd() : vanillaCloudFadeEnd; }
    public float vanillaCloudHeight() { return SkyboxServerState.enforced() ? SkyboxServerState.get().cloudHeight() : vanillaCloudHeight; }
    /** Connected clients always use the server's immutable environment snapshot. */
    public SkyEnvironment environment() { return SkyboxServerState.enforced() ? SkyboxServerState.get().environment() : environment; }
    /** True when any environment attribute came from a JVM {@code -Dskyboxblock.environment.*} flag. */
    public static boolean environmentFromJvm() {
        for (String name : System.getProperties().stringPropertyNames())
            if (name.startsWith(SkyEnvironment.JVM_PREFIX)) return true;
        return false;
    }
    /**
     * Persists one environment override (already validated by {@link SkyEnvironment#parse}).
     * Rewrites the whole file atomically, keeping every other key — known or unknown — untouched.
     */
    public static void setEnvironment(String id,String value) throws IOException {
        String key=SkyEnvironment.key(requireAttribute(id));
        update(properties -> {
            // Drop every alias of this attribute first so no stale alias can shadow the new value.
            aliases(properties,key).forEach(properties::remove);
            properties.setProperty(key,value);
        });
    }
    /** Removes every alias of one environment override, returning the attribute to {@code auto}. */
    public static void resetEnvironment(String id) throws IOException {
        String key=SkyEnvironment.key(requireAttribute(id));
        update(properties -> aliases(properties,key).forEach(properties::remove));
    }
    /** Every on-disk key naming the attribute that {@code canonicalKey} belongs to. */
    private static java.util.List<String> aliases(Properties properties,String canonicalKey) {
        var attribute=SkyEnvironment.attribute(canonicalKey.substring(SkyEnvironment.KEY_PREFIX.length()));
        return SkyEnvironment.aliasedKeys(properties.stringPropertyNames(),attribute);
    }
    /** Removes all environment overrides and the private clock; legacy cloud keys survive. */
    public static void resetAllEnvironment() throws IOException {
        update(properties -> {
            properties.stringPropertyNames().stream().filter(name -> name.startsWith(SkyEnvironment.KEY_PREFIX))
                    .toList().forEach(properties::remove);
            properties.remove("skyTimeMode");properties.remove("skyTimeTicks");
        });
    }
    public SkyTimeClock.Setting skyTime() { return SkyboxServerState.enforced() ? SkyboxServerState.get().clock() : skyTime; }
    public static boolean skyTimeFromJvm() {
        return System.getProperty("skyboxblock.skyTimeMode")!=null || System.getProperty("skyboxblock.skyTimeTicks")!=null;
    }
    public static void setSkyTime(SkyTimeClock.Setting setting) throws IOException {
        java.util.Objects.requireNonNull(setting,"sky clock setting");
        if(setting.mode()==SkyTimeClock.Mode.AUTO) { resetSkyTime();return; }
        update(properties -> {
            properties.setProperty("skyTimeMode",setting.mode().id());
            properties.setProperty("skyTimeTicks",Integer.toString(setting.ticks()));
        });
    }
    public static void resetSkyTime() throws IOException {
        update(properties -> { properties.remove("skyTimeMode");properties.remove("skyTimeTicks"); });
    }
    private static dev.yg.skyboxblock.SkyEnvironment.Attribute requireAttribute(String id) {
        var attribute=SkyEnvironment.attribute(id);
        if (attribute==null) throw new IllegalArgumentException("未知的环境属性："+id);
        return attribute;
    }
    public static void reload() { instance=null; get(); SkyVanillaClouds.invalidate(); }
    /**
     * Re-reads the on-disk file for environment attributes only: the fresh snapshot is live on the
     * next {@link #environment()} call, without invalidating the vanilla cloud mesh.
     */
    public static void reloadEnvironment() {
        instance=null;
        get();
    }
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
    /**
     * Read-modify-write of the property file, atomically. Writing is <em>fail-closed</em>: a file that
     * cannot be parsed is never rewritten or truncated — the parse failure is wrapped in
     * {@link IOException} so the caller reports it and the snapshot stays untouched.
     */
    private static void update(java.util.function.Consumer<Properties> mutation) throws IOException {
        if(SkyboxServerState.enforced()) throw new IOException("天空盒配置由服务端管理，请使用 /skyboxblock env");
        var file=configFile();
        Properties properties=new Properties();
        if (Files.exists(file)) {
            try (var stream=Files.newInputStream(file)) { properties.load(stream); }
            catch (IllegalArgumentException exception) {
                // Malformed Unicode escape or similar: refuse to write so the user's file is preserved.
                throw new IOException("配置文件无法解析，已放弃写入以免覆盖："+file.getFileName(),exception);
            }
        }
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
    public boolean simulatedLocalWeather() { return SkyboxServerState.enforced() ? SkyboxServerState.get().simulatedLocalWeather() : simulatedLocalWeather; }
    public boolean clouds() { return SkyboxServerState.enforced() ? SkyboxServerState.get().clouds() : clouds; }
    public boolean debug() { return debug; }
}
