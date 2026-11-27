package dev.yg.skyboxblock.server;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import dev.yg.skyboxblock.SkyboxSettings;
import dev.yg.skyboxblock.network.SkyboxNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Server-owned persistent sky settings; this class has no Minecraft client dependencies. */
public final class SkyboxServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("skyboxblock");
    private static final Map<MinecraftServer, Store> STORES = new IdentityHashMap<>();
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static Path testFile;
    private static Store testStore;

    private SkyboxServerConfig() { }

    public static synchronized Store get(MinecraftServer server) {
        if (server == null) throw new IllegalStateException("天空盒设置需要正在运行的服务器");
        return STORES.computeIfAbsent(server, owner -> new Store(
                owner.getWorldPath(LevelResource.ROOT).resolve("serverconfig/skyboxblock.properties"), owner));
    }

    public static Store forSource(CommandSourceStack source) {
        MinecraftServer server = source == null ? null : source.getServer();
        if (server != null) return get(server);
        synchronized (SkyboxServerConfig.class) {
            if (testFile != null) {
                if (testStore == null) testStore = new Store(testFile, null);
                return testStore;
            }
        }
        throw new IllegalStateException("天空盒设置需要服务器；未安装测试文件钩子");
    }

    public static long gameTime(CommandSourceStack source) {
        MinecraftServer server = source == null ? null : source.getServer();
        return server == null ? 0L : Math.max(0L, server.overworld().getGameTime());
    }

    public static double automaticTicks(CommandSourceStack source) {
        if (source == null || source.getServer() == null || source.getLevel() == null) return 0.0;
        var level = source.getLevel();
        SkyboxSettings settings = forSource(source).settings();
        // 26.2：DimensionSpecialEffects 已删除，天空类型改由数据驱动的 DimensionType.Skybox 枚举表达。
        var skybox = level.dimensionType().skybox();
        boolean normalSky = skybox == net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD;
        if (settings.usesLocalTime(level.dimension().identifier().toString(), normalSky))
            // Clock time is measured in ticks, not a normalized solar phase. Read the same
            // data-driven angle used by native SkyRenderer before capturing AUTO into LOCKED.
            return automaticTicksFromSunAngle(level.environmentAttributes().getDimensionValue(
                    net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE));
        return SkyTimeClock.normalize(level.getGameTime());
    }

    static double automaticTicksFromSunAngle(float degrees) {
        return SkyTimeClock.ticksForPhase(degrees / 360F);
    }

    public static synchronized void installTestFile(Path file) {
        testFile = java.util.Objects.requireNonNull(file, "test file").toAbsolutePath().normalize();
        testStore = null;
    }

    public static synchronized void clearTestFile() { testFile = null; testStore = null; }

    /** Called only by the server lifecycle hook, not by a client disconnect. */
    public static synchronized void release(MinecraftServer server) { STORES.remove(server); }

    public static final class Store {
        private final Path file;
        private final MinecraftServer server;
        private volatile SkyboxSettings settings = SkyboxSettings.DEFAULT;
        /** Immutable-by-convention properties from the last successful load, commit or reload. */
        private volatile Properties globalProperties = new Properties();
        /** Lazily loaded per-player overrides; null until first use. */
        private java.util.Map<java.util.UUID, Properties> overrides;
        /** Monotonic counter so every sent snapshot has a revision the client will accept. */
        private long sendSequence;

        private Store(Path file, MinecraftServer server) {
            this.file = file.toAbsolutePath().normalize();
            this.server = server;
            try {
                Properties loaded = read(file);
                settings = parse(loaded, 0L);
                globalProperties = copyProperties(loaded);
            } catch (IOException | IllegalArgumentException error) {
                // Never retain partially parsed Properties or overwrite the damaged source.
                LOGGER.warn("[skyboxblock] server config read failed; defaults remain active: {}", this.file, error);
            }
        }

        public SkyboxSettings settings() { return settings; }
        public Path file() { return file; }

        public void setEnvironment(String id, String value) throws IOException {
            var attribute = requireAttribute(id);
            if (value != null && value.trim().equalsIgnoreCase(SkyEnvironment.AUTO)) {
                resetEnvironment(attribute.id());
                return;
            }
            String canonicalValue = SkyEnvironment.parse(attribute, value).text();
            commit(properties -> {
                SkyEnvironment.aliasedKeys(properties.stringPropertyNames(), attribute).forEach(properties::remove);
                properties.setProperty(SkyEnvironment.key(attribute), canonicalValue);
            });
        }

        public void resetEnvironment(String id) throws IOException {
            var attribute = requireAttribute(id);
            commit(properties -> SkyEnvironment.aliasedKeys(properties.stringPropertyNames(), attribute).forEach(properties::remove));
        }

        /** Clear the known 15 overrides and private clock; preserve unknown and legacy keys. */
        public void resetAll() throws IOException {
            commit(properties -> {
                for (var attribute : SkyEnvironment.attributes())
                    SkyEnvironment.aliasedKeys(properties.stringPropertyNames(), attribute).forEach(properties::remove);
                removeClock(properties);
            });
        }

        /** Strict reload: a damaged/invalid file never replaces the last good snapshot. */
        public synchronized void reload() throws IOException {
            SkyboxSettings next;
            Properties loaded = read(file);
            try { next = parse(loaded, nextRevision()); }
            catch (IllegalArgumentException error) { throw new IOException("天空盒服务器配置无效，保留原状态", error); }
            globalProperties = copyProperties(loaded);
            settings = next;
            publish(next);
        }

        /**
         * Sets the displayed private-sky time. A locked clock stays locked and only its frozen tick
         * changes; an automatic or running clock becomes running from {@code ticks} at {@code now}.
         */
        public synchronized void setTime(int ticks, long now) throws IOException {
            long anchor = requireTime(now);
            SkyTimeClock.Mode mode = settings.clock().mode() == SkyTimeClock.Mode.LOCKED
                    ? SkyTimeClock.Mode.LOCKED : SkyTimeClock.Mode.RUNNING;
            SkyTimeClock.Setting clock = new SkyTimeClock.Setting(mode, ticks);
            commit(properties -> putClock(properties, clock, anchor));
        }

        public synchronized void lockTime(Integer ticks, long now, double autoTicks) throws IOException {
            long anchor = requireTime(now);
            int captured = ticks == null ? (int) Math.floor(SkyTimeClock.normalize(settings.clockTicks(now, 0F, autoTicks))) : ticks;
            SkyTimeClock.Setting clock = new SkyTimeClock.Setting(SkyTimeClock.Mode.LOCKED, captured);
            commit(properties -> putClock(properties, clock, anchor));
        }

        public synchronized void unlockTime(long now) throws IOException {
            long anchor = requireTime(now);
            SkyboxSettings previous = settings;
            if (previous.clock().mode() != SkyTimeClock.Mode.LOCKED) return;
            SkyTimeClock.Setting clock = new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING, previous.clock().ticks());
            commit(properties -> putClock(properties, clock, anchor));
        }

        public void resetTime() throws IOException { commit(SkyboxServerConfig::removeClock); }

        public String timeDescription(long now, double autoTicks) {
            SkyboxSettings current = settings;
            return "time = " + (int) Math.floor(SkyTimeClock.normalize(current.clockTicks(now, 0F, autoTicks)))
                    + " / " + current.clock().mode().id();
        }

        private long nextRevision() throws IOException {
            if (settings.revision() == Long.MAX_VALUE) throw new IOException("天空盒配置 revision 已到上限");
            return settings.revision() + 1L;
        }

        private synchronized void commit(Consumer<Properties> mutation) throws IOException {
            // Re-read first: external unknown keys survive, and damaged files are never silently repaired.
            Properties properties = read(file);
            mutation.accept(properties);
            SkyboxSettings next;
            try { next = parse(properties, nextRevision()); }
            catch (IllegalArgumentException error) { throw new IOException("天空盒服务器配置无效，未写入", error); }
            persist(file, properties);
            globalProperties = copyProperties(properties);
            settings = next; // Only after successful atomic file replacement.
            publish(next);
        }

        private void publish(SkyboxSettings snapshot) {
            if (server == null) return;
            // Per-player overrides mean each client may need a different snapshot, and the client
            // drops anything whose revision does not increase, so every send carries a fresh one.
            for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers())
                SkyboxNetworking.send(player, forSend(player.getUUID()));
        }

        /** Snapshot prepared for transmission: this player's effective values with a rising revision. */
        public synchronized SkyboxSettings forSend(java.util.UUID player) {
            SkyboxSettings effective = settingsFor(player);
            long revision = nextSendRevision(sendSequence, effective.revision());
            sendSequence = revision;
            return new SkyboxSettings(effective.environment(), effective.clouds(), effective.cloudHeight(),
                    effective.cloudFadeStart(), effective.cloudFadeEnd(), effective.simulatedLocalWeather(),
                    effective.localTimeDimensions(), effective.simulatedTimeDimensions(), effective.clock(),
                    effective.clockAnchor(), revision);
        }

        /** Allocate one strictly increasing wire revision, independent of persisted settings revisions. */
        static long nextSendRevision(long current, long minimum) {
            if (current < 0L || minimum < 0L)
                throw new IllegalArgumentException("server snapshot revisions must be non-negative");
            if (current == Long.MAX_VALUE)
                throw new IllegalStateException("server snapshot revision sequence exhausted");
            return Math.max(current + 1L, minimum);
        }

        // ---------------------------------------------------------------- per-player overrides

        /** Effective settings for one player: the global snapshot with that player's keys applied. */
        public synchronized SkyboxSettings settingsFor(java.util.UUID player) {
            return settingsFor(player, settings);
        }

        private synchronized SkyboxSettings settingsFor(java.util.UUID player, SkyboxSettings global) {
            Properties overrides = playerOverrides().get(player);
            if (overrides == null || overrides.isEmpty()) return global;
            Properties merged = copyProperties(globalProperties);
            merged.putAll(overrides);
            try {
                return parse(merged, global.revision());
            } catch (RuntimeException error) {
                LOGGER.warn("[skyboxblock] player override for {} ignored", player, error);
                return global;
            }
        }

        public boolean hasOverride(java.util.UUID player) {
            Properties overrides = playerOverrides().get(player);
            return overrides != null && !overrides.isEmpty();
        }

        /** Same attribute set and validation as the global command, written to the player's scope. */
        public synchronized void setPlayerEnvironment(java.util.UUID player, String id, String value)
                throws IOException {
            var attribute = requireAttribute(id);
            if (value != null && value.trim().equalsIgnoreCase(SkyEnvironment.AUTO)) {
                resetPlayerEnvironment(player, attribute.id());
                return;
            }
            String canonical = SkyEnvironment.parse(attribute, value).text();
            commitPlayer(player, properties -> {
                SkyEnvironment.aliasedKeys(properties.stringPropertyNames(), attribute).forEach(properties::remove);
                properties.setProperty(SkyEnvironment.key(attribute), canonical);
            });
        }

        public synchronized void resetPlayerEnvironment(java.util.UUID player, String id) throws IOException {
            var attribute = requireAttribute(id);
            commitPlayer(player, properties ->
                    SkyEnvironment.aliasedKeys(properties.stringPropertyNames(), attribute).forEach(properties::remove));
        }

        public synchronized void setPlayerTime(java.util.UUID player, int ticks, long now) throws IOException {
            long anchor = requireTime(now);
            SkyboxSettings current = settingsFor(player);
            SkyTimeClock.Mode mode = current.clock().mode() == SkyTimeClock.Mode.LOCKED
                    ? SkyTimeClock.Mode.LOCKED : SkyTimeClock.Mode.RUNNING;
            commitPlayer(player, properties -> putClock(properties, new SkyTimeClock.Setting(mode, ticks), anchor));
        }

        public synchronized void resetPlayerAll(java.util.UUID player) throws IOException {
            commitPlayer(player, Properties::clear);
        }

        public synchronized void resetPlayerTime(java.util.UUID player) throws IOException {
            commitPlayer(player, SkyboxServerConfig::removeClock);
        }

        private synchronized void commitPlayer(java.util.UUID player, Consumer<Properties> mutation)
                throws IOException {
            Properties global = copyProperties(globalProperties);
            // Mutate a private deep copy. A validation or atomic-write failure must not leak into the
            // live cache or be smuggled into a later player's successful transaction.
            java.util.Map<java.util.UUID, Properties> candidate = copyPlayerOverrides(playerOverrides());
            Properties properties = candidate.computeIfAbsent(player, ignored -> new Properties());
            mutation.accept(properties);
            Properties merged = new Properties();
            merged.putAll(global);
            merged.putAll(properties);
            try { parse(merged, settings.revision()); }
            catch (IllegalArgumentException error) { throw new IOException("玩家天空盒配置无效，未写入", error); }
            persistPlayers(candidate);
            overrides = candidate;
            publish(settings);
        }

        private static Properties copyProperties(Properties source) {
            Properties copy = new Properties();
            copy.putAll(source);
            return copy;
        }

        private static java.util.Map<java.util.UUID, Properties> copyPlayerOverrides(
                java.util.Map<java.util.UUID, Properties> source) {
            var copy = new java.util.LinkedHashMap<java.util.UUID, Properties>();
            source.forEach((player, current) -> copy.put(player, copyProperties(current)));
            return copy;
        }

        /** Loads the override file once and caches it; writes always refresh the cache. */
        private java.util.Map<java.util.UUID, Properties> playerOverrides() {
            java.util.Map<java.util.UUID, Properties> cached = overrides;
            if (cached != null) return cached;
            cached = new java.util.LinkedHashMap<>();
            String prefix = "player.";
            try {
                Properties flat = read(playersFile());
                for (String name : flat.stringPropertyNames()) {
                    if (!name.startsWith(prefix)) continue;
                    int dot = name.indexOf('.', prefix.length());
                    if (dot < 0) continue;
                    try {
                        java.util.UUID id = java.util.UUID.fromString(name.substring(prefix.length(), dot));
                        cached.computeIfAbsent(id, ignored -> new Properties())
                                .setProperty(name.substring(dot + 1), flat.getProperty(name));
                    } catch (IllegalArgumentException malformed) {
                        LOGGER.warn("[skyboxblock] ignoring malformed player override {}", name);
                    }
                }
            } catch (IOException error) {
                LOGGER.warn("[skyboxblock] player overrides unreadable; none applied", error);
            }
            overrides = cached;
            return cached;
        }

        private void persistPlayers(java.util.Map<java.util.UUID, Properties> snapshot) throws IOException {
            Properties flat = new Properties();
            snapshot.forEach((id, properties) -> properties.forEach((key, value) ->
                    flat.setProperty("player." + id + "." + key, String.valueOf(value))));
            persist(playersFile(), flat);
        }

        private Path playersFile() {
            return file.resolveSibling("skyboxblock-players.properties");
        }
    }

    private static long requireTime(long now) {
        if (now < 0L) throw new IllegalArgumentException("服务器 gameTime 应为非负数");
        return now;
    }

    private static SkyEnvironment.Attribute requireAttribute(String id) {
        var attribute = SkyEnvironment.attribute(id);
        if (attribute == null) throw new IllegalArgumentException("未知的环境属性：" + id);
        return attribute;
    }

    private static void removeClock(Properties properties) {
        properties.remove("skyTimeMode"); properties.remove("skyTimeTicks"); properties.remove("skyTimeAnchor");
    }

    private static void putClock(Properties properties, SkyTimeClock.Setting clock, long anchor) {
        properties.setProperty("skyTimeMode", clock.mode().id());
        properties.setProperty("skyTimeTicks", Integer.toString(clock.ticks()));
        properties.setProperty("skyTimeAnchor", Long.toString(anchor));
    }

    private static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
        catch (java.nio.file.NoSuchFileException missing) { return properties; }
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("天空盒服务器配置超过 1 MiB");
        try (var input = new ByteArrayInputStream(bytes)) { properties.load(input); }
        catch (IllegalArgumentException error) { throw new IOException("天空盒服务器配置解析失败，原文件保持不变", error); }
        return properties;
    }

    private static SkyboxSettings parse(Properties properties, long revision) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String name : new TreeSet<>(properties.stringPropertyNames())) entries.put(name, properties.getProperty(name));
        for (var entry : SkyEnvironment.canonicalEntries(entries).entrySet()) {
            var attribute = SkyEnvironment.attribute(entry.getKey());
            if (attribute != null && !entry.getValue().trim().equalsIgnoreCase(SkyEnvironment.AUTO))
                SkyEnvironment.parse(attribute, entry.getValue());
        }
        SkyEnvironment environment = SkyEnvironment.fromEntries(entries);
        float end = Float.parseFloat(properties.getProperty("vanillaCloudFadeEnd", "384").trim());
        String startText = properties.getProperty("vanillaCloudFadeStart");
        float start = startText == null ? (128F < end ? 128F : end / 3F) : Float.parseFloat(startText.trim());
        var mode = SkyTimeClock.Mode.parse(properties.getProperty("skyTimeMode", "auto"));
        int ticks = Integer.parseInt(properties.getProperty("skyTimeTicks", "0").trim());
        long anchor = Long.parseLong(properties.getProperty("skyTimeAnchor", "0").trim());
        String weather = properties.getProperty("simulatedWeather", "clear").trim();
        if (!weather.equalsIgnoreCase("clear") && !weather.equalsIgnoreCase("local"))
            throw new IllegalArgumentException("simulatedWeather 应为 clear 或 local");
        return new SkyboxSettings(environment, bool(properties.getProperty("clouds", "true")),
                Float.parseFloat(properties.getProperty("vanillaCloudHeight", "192").trim()), start, end,
                weather.equalsIgnoreCase("local"), dimensions(properties.getProperty("localTimeDimensions", "")),
                dimensions(properties.getProperty("simulatedTimeDimensions", "")), new SkyTimeClock.Setting(mode, ticks), anchor, revision);
    }

    private static boolean bool(String value) {
        if (value.trim().equalsIgnoreCase("true")) return true;
        if (value.trim().equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("clouds 应为 true 或 false");
    }

    private static Set<String> dimensions(String value) {
        Set<String> result = Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (result.size() > 32) throw new IllegalArgumentException("维度时间策略最多 32 项");
        return result;
    }

    private static void persist(Path file, Properties properties) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("服务器配置缺少父目录");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".skyboxblock-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                OutputStream output = Channels.newOutputStream(channel);
                properties.store(output, "skyboxblock authoritative server settings");
                output.flush();
                channel.force(true);
            }
            // No non-atomic fallback: failure preserves both the old file and live snapshot.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException cleanupFailure) { LOGGER.warn("[skyboxblock] failed config temporary file retained: {}", temporary, cleanupFailure); }
            }
        }
    }
}