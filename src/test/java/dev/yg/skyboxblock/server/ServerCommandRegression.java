package dev.yg.skyboxblock.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/**
 * Real-Brigadier / real-Config regression for the server-authoritative {@code /skyboxblock} tree.
 *
 * <p>Every case parses and executes through the tree returned by
 * {@link SkyboxServerCommands#buildTree}, against the real {@link CommandSourceStack} (built on
 * {@link CommandSource#NULL} so no live server, network or player is involved) and the real
 * {@link SkyboxServerConfig} store test hook. Nothing is simulated: unknown ids, extra tokens, strict
 * positivity, permission gating, one-form-only TAB and the removed root commands are all observed
 * through the same dispatcher and the same file the server uses.
 *
 * <p>Usage: {@code java dev.yg.skyboxblock.server.ServerCommandRegression [report.txt]}
 */
public final class ServerCommandRegression {
    private static int checks;

    private static void require(boolean pass, String text) {
        checks++;
        if (!pass) throw new AssertionError(text);
    }
    private static void equal(Object expected, Object actual, String text) {
        require(java.util.Objects.equals(expected, actual), text + ": expected " + expected + " but was " + actual);
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("skybox-server-command");
        configFile = directory.resolve("skyboxblock-server.properties");
        restart();
        try {
            sourceTree();
            routeRegression();
            readRegression();
            setResetRegression();
            permissionsRegression();
            rejectionRegression();
            removedCommandsRegression();
            reloadRegression();
            timeRegression();
            suggestionRegression();
        } finally {
            SkyboxServerConfig.clearTestFile();
            deleteQuietly(directory);
        }
        System.out.println("ServerCommandRegression: " + checks + " checks OK");
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "ServerCommandRegression: " + checks + " checks OK\n", StandardCharsets.UTF_8);
        }
    }

    private static CommandDispatcher<CommandSourceStack> tree() {
        return SkyboxServerCommands.buildTree(new CommandDispatcher<>());
    }

    /**
     * A real {@link CommandSource} actor that records what the command sent. The stack is built on
     * this actor (not {@code CommandSource.NULL}), so success/failure text is observed exactly the
     * way the server sends it — no test-only getter is added to production code.
     */
    private static final class Capture implements CommandSource {
        final List<String> success = new ArrayList<>(), failure = new ArrayList<>();
        final List<CommandSourceStack> stacks = new ArrayList<>();

        @Override public void sendSystemMessage(Component message) { success.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }

        void clear() { success.clear(); failure.clear(); }
    }

    /** An operator-4 source and a plain player source, each on its own capturing actor. */
    private static CommandSourceStack stack(Capture actor, int level, String name) {
        CommandSourceStack source = new CommandSourceStack(actor, net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.world.phys.Vec2.ZERO, null, level, name, Component.literal(name), null, null);
        actor.stacks.add(source);
        return source;
    }

    private static Capture operator() { return new Capture(); }

    private static Capture player() { return new Capture(); }

    private static CommandSourceStack operator(Capture actor) { return stack(actor, 4, "operator"); }

    private static CommandSourceStack player(Capture actor) { return stack(actor, 0, "player"); }

    /** Everything the command reported (success and failure), in order. */
    private static String text(Capture actor) {
        StringBuilder out = new StringBuilder();
        for (String line : actor.success) out.append(line);
        for (String line : actor.failure) out.append(line);
        return out.toString();
    }

    private static int reported(Capture actor) { return actor.success.size() + actor.failure.size(); }

    // ── source construction ───────────────────────────────────────────────────────────────

    /** The real source objects behave as the tree expects: permission level on the stack. */
    private static void sourceTree() {
        require(operator(new Capture()).hasPermission(SkyboxServerCommands.MUTATION_LEVEL),
                "operator has permission 2");
        require(!player(new Capture()).hasPermission(SkyboxServerCommands.MUTATION_LEVEL),
                "player lacks permission 2");
    }

    // ── routes ────────────────────────────────────────────────────────────────────────────

    private static void routeRegression() {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        Capture actor = operator();
        CommandSourceStack source = operator(actor);
        for (SkyEnvironment.Attribute attribute : SkyEnvironment.attributes()) {
            List<String> forms = forms(attribute);
            equal(3, forms.size(), "three id forms per attribute");
            for(String id:forms) {
                String value=sampleValue(attribute);
                require(succeeds(dispatcher,"skyboxblock env set "+id+" "+value,source),"set accepts ID form "+id);
                require(succeeds(dispatcher,"skyboxblock env reset "+id,source),"reset accepts ID form "+id);
            }
        }
        for (String input : new String[]{"skyboxblock", "skyboxblock environment",
                "skyboxblock environment list", "skyboxblock env list",
                "skyboxblock environment reset all", "skyboxblock env reset all",
                "skyboxblock environment reload", "skyboxblock env reload",
                "skyboxblock environment time", "skyboxblock environment time get"})
            require(succeeds(dispatcher, input, source), "route exists: " + input);
        // No invented nested root node.
        require(dispatcher.getRoot().getChild("skyboxblock").getChild("skyboxblock") == null,
                "no nested skyboxblock root");
        // The tree is serialisable to clients: only built-in argument types, one attribute node.
        serialisationRegression(dispatcher);
    }

    /** The three id forms of one attribute, as the player may type them. */
    static List<String> forms(SkyEnvironment.Attribute attribute) {
        return List.of(attribute.shortId(), attribute.id(), SkyEnvironment.NAMESPACE + attribute.id());
    }

    private static String sampleValue(SkyEnvironment.Attribute attribute) {
        return switch(attribute.kind()) {
            case RGB -> "#AABBCC";
            case ARGB -> "#FFAABBCC";
            case FLAG -> "true";
            case PHASE -> "full_moon";
            case NUMBER -> switch(attribute.id()) {
                case "visual/cloud_height" -> "192.33";
                case "visual/star_brightness" -> "0.5";
                default -> attribute.positive() ? "96" : "0";
            };
        };
    }

    /**
     * The command tree must survive {@code ClientboundCommandsPacket}: no custom argument types and
     * no custom literal nodes, only the vanilla resource-location id and greedy strings, so a real
     * client receives the same single-form TAB instead of 45 duplicate literals.
     */
    private static void serialisationRegression(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        List<String> problems = new ArrayList<>();
        walk(root, problems, "");
        require(problems.isEmpty(), "tree only uses built-in nodes: " + problems);
        // The attribute node really is the vanilla resource-location argument.
        CommandNode<CommandSourceStack> environment = root.getChild("skyboxblock").getChild("environment");
        CommandNode<CommandSourceStack> attribute = environment.getChild(SkyboxServerCommands.ATTRIBUTE);
        require(attribute instanceof ArgumentCommandNode<CommandSourceStack, ?>, "attribute node is an argument");
        ArgumentType<?> type = ((ArgumentCommandNode<CommandSourceStack, ?>) attribute).getType();
        require(type instanceof ResourceLocationArgument, "attribute uses ResourceLocationArgument: " + type);
        // Every id form stays a plain value of that one argument, never a literal alias.
        List<String> aliasForms = new ArrayList<>();
        for (SkyEnvironment.Attribute candidate : SkyEnvironment.attributes()) aliasForms.addAll(forms(candidate));
        for (CommandNode<CommandSourceStack> child : attribute.getChildren())
            require(!aliasForms.contains(child.getName()), "no per-attribute literal alias: " + child.getName());
        require(attribute.getChildren().stream().noneMatch(node -> node instanceof LiteralCommandNode
                        && aliasForms.contains(node.getName())),
                "no per-attribute literal aliases remain");
        require(environment.getChild("get").getChild(SkyboxServerCommands.ATTRIBUTE) != null,
                "get uses the shared attribute node");
        require(environment.getChild("reset").getChild(SkyboxServerCommands.ATTRIBUTE) != null,
                "reset uses the shared attribute node");
    }

    /** Collects every node that a vanilla command packet cannot carry. */
    private static void walk(CommandNode<CommandSourceStack> node, List<String> problems, String path) {
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            ArgumentType<?> type = argument.getType();
            boolean builtIn = type instanceof ResourceLocationArgument || type instanceof StringArgumentType;
            if (!builtIn) problems.add(path + " -> " + type.getClass().getName());
        } else if (!(node instanceof RootCommandNode || node instanceof LiteralCommandNode)) {
            problems.add(path + " -> " + node.getClass().getName());
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren())
            walk(child, problems, path + " " + child.getName());
    }

    // ── reads ─────────────────────────────────────────────────────────────────────────────

    private static void readRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        // list: one line per attribute, bare auto, no per-line qualifier.
        Capture asPlayer = player();
        CommandSourceStack source = player(asPlayer);
        require(succeeds(dispatcher, "skyboxblock environment list", source), "list runs as a player");
        String listing = text(asPlayer);
        for (SkyEnvironment.Attribute attribute : SkyEnvironment.attributes())
            require(listing.contains("  " + attribute.id() + " = "), "list mentions " + attribute.id());
        require(listing.contains("auto"), "list shows auto");
        require(!listing.contains("（已覆盖）") && !listing.contains("（auto）"), "list has no qualifier: " + listing);
        require(!listing.contains("JVM") && !listing.contains("已保存并立即生效"), "no client-era wording: " + listing);
        // get: exactly one line, `id = auto`.
        Capture getActor = player();
        require(succeeds(dispatcher, "skyboxblock environment get visual/sky_color", player(getActor)),
                "get as a player");
        equal("visual/sky_color = auto", text(getActor), "get is one line");
        // bare id also only reads.
        Capture bare = player();
        require(succeeds(dispatcher, "skyboxblock env sky_color", player(bare)), "bare id as a player");
        equal("visual/sky_color = auto", text(bare), "bare id is one line");
    }

    // ── set / reset ──────────────────────────────────────────────────────────────────────

    private static void setResetRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        set(dispatcher, "skyboxblock environment set visual/sky_color #7ba4ff", "visual/sky_color = #7BA4FF");
        equal("#7BA4FF", environment().effective("visual/sky_color"), "sky colour stored");
        equal("environment.visual/sky_color=#7BA4FF", findProperty("environment.visual/sky_color"),
                "sky colour written to the server file");
        set(dispatcher, "skyboxblock env fog_end_distance 1024", "visual/fog_end_distance = 1024");
        set(dispatcher, "skyboxblock env cloud_height 240.33", "visual/cloud_height = 240.33");
        set(dispatcher, "skyboxblock env cloud_fog_end_distance 768", "visual/cloud_fog_end_distance = 768");
        set(dispatcher, "skyboxblock env fog_start_distance 100", "visual/fog_start_distance = 100");
        set(dispatcher, "skyboxblock env moon_phase 7", "visual/moon_phase = waxing_gibbous");
        set(dispatcher, "skyboxblock env has_sky_occluder true", "visual/has_sky_occluder = true");
        // reset one attribute, then `set <id> auto` as an explicit reset.
        Capture resetActor = operator();
        require(succeeds(dispatcher, "skyboxblock env reset sky_color", operator(resetActor)), "reset one");
        equal("visual/sky_color = auto", text(resetActor), "reset line is id = auto");
        require(!environment().has("visual/sky_color"), "reset clears the override");
        require(environment().has("visual/fog_end_distance"), "reset keeps other attributes");
        require(succeeds(dispatcher, "skyboxblock env fog_end_distance auto", operator(new Capture())), "set auto");
        require(!environment().has("visual/fog_end_distance"), "set auto clears the override");
        // reset all clears every environment key and keeps unrelated lines.
        Properties before = load(file());
        before.setProperty("future.option", "keep");
        store(file(), before);
        require(succeeds(dispatcher, "skyboxblock environment reset all", operator(new Capture())), "reset all");
        Properties after = load(file());
        require(after.stringPropertyNames().stream().noneMatch(name -> name.startsWith("environment.")),
                "reset all clears environment keys: " + after);
        equal("keep", after.getProperty("future.option"), "reset all keeps unrelated keys");
        require(environment().overrides().isEmpty(), "reset all clears the live snapshot");
    }

    private static void set(CommandDispatcher<CommandSourceStack> dispatcher, String input, String expected) {
        Capture actor = operator();
        require(succeeds(dispatcher, input, operator(actor)), "set succeeds: " + input);
        equal(expected, text(actor), "set line for " + input);
    }

    // ── permissions ──────────────────────────────────────────────────────────────────────

    /** Every mutation is gated; reads are not. The shortcut routes must be gated too. */
    private static void permissionsRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        require(succeeds(dispatcher,"skyboxblock env set sky_color #112233",operator(new Capture())),"seed permission baseline");
        String seeded = Files.readString(file(), StandardCharsets.UTF_8);
        for (String input : new String[]{
                "skyboxblock env sky_color #112233",              // bare id + value shortcut
                "skyboxblock env sky_color reset",                // bare id + reset shortcut
                "skyboxblock env set sky_color #112233",
                "skyboxblock environment set visual/sky_color #112233",
                "skyboxblock env reset sky_color",
                "skyboxblock env reset all",
                "skyboxblock env reload",
                "skyboxblock env time set noon",
                "skyboxblock env time lock",
                "skyboxblock env time unlock",
                "skyboxblock env time reset"}) {
            Capture actor = player();
            CommandSourceStack source = player(actor);
            boolean rejected;
            try { rejected = dispatcher.execute(input, source) <= 0; }
            catch (CommandSyntaxException expected) { rejected = true; }
            require(rejected, "player is refused: " + input);
            equal(seeded, Files.readString(file(), StandardCharsets.UTF_8), "player changed nothing: " + input);
        }
        // The same reads stay open.
        for (String input : new String[]{"skyboxblock", "skyboxblock env list", "skyboxblock env get sky_color",
                "skyboxblock env sky_color", "skyboxblock env time", "skyboxblock env time get"}) {
            Capture actor = player();
            require(succeeds(dispatcher, input, player(actor)), "player may read: " + input);
            require(actor.failure.isEmpty(), "no failure for a permitted read: " + input);
        }
    }

    // ── rejections ───────────────────────────────────────────────────────────────────────

    private static void rejectionRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        require(succeeds(dispatcher, "skyboxblock env fog_end_distance 2048", operator(new Capture())), "seed value");
        String before = Files.readString(file(), StandardCharsets.UTF_8);
        for (String input : new String[]{
                "skyboxblock env fog_end_distance 0",
                "skyboxblock env fog_end_distance -1",
                "skyboxblock env fog_end_distance NaN",
                "skyboxblock env fog_end_distance Infinity",
                "skyboxblock env fog_end_distance 40000",
                "skyboxblock env sky_fog_end_distance 0",
                "skyboxblock env cloud_fog_end_distance 0",
                "skyboxblock env cloud_fog_end_distance 4097",
                "skyboxblock env sky_color #12345",
                "skyboxblock env sky_color #GGGGGG",
                "skyboxblock env sky_color red",
                "skyboxblock env cloud_color #FFFFFFFFF",
                "skyboxblock env has_sky_occluder yes",
                "skyboxblock env moon_phase 8",
                "skyboxblock env moon_phase harvest_moon",
                "skyboxblock env star_brightness 1.5",
                "skyboxblock env sun_angle Infinity",
                "skyboxblock env unknown_attribute 1",
                "skyboxblock env visual/unknown_attribute 1",
                "skyboxblock env sky_color #7BA4FF garbage",
                "skyboxblock env fog_end_distance 1024 2048",
                "skyboxblock env set sky_color",
                "skyboxblock env get unknown_attribute",
                "skyboxblock env reset unknown_attribute"}) {
            Capture actor = operator();
            int result = execute(dispatcher, input, operator(actor));
            require(result <= 0, "rejected: " + input);
            equal(before, Files.readString(file(), StandardCharsets.UTF_8), "file unchanged after: " + input);
        }
        // A valid set still works after the failures.
        require(succeeds(dispatcher, "skyboxblock env sky_color #010203", operator(new Capture())),
                "set after failures");
    }

    // ── removed root commands ────────────────────────────────────────────────────────────

    private static void removedCommandsRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        require(succeeds(dispatcher,"skyboxblock env set sky_color #112233",operator(new Capture())),"seed removed-command baseline");
        String before = Files.readString(file(), StandardCharsets.UTF_8);
        for (String input : new String[]{"skyboxblock cloudheight", "skyboxblock cloudheight 240",
                "skyboxblock cloudheight reset", "skyboxblock clouddistance", "skyboxblock clouddistance 384",
                "skyboxblock clouddistance reset", "skyboxblock cloudfade", "skyboxblock cloudfade 100 400",
                "skyboxblock cloudfade reset", "skyboxblock reload"}) {
            Capture actor = operator();
            int result = execute(dispatcher, input, operator(actor));
            require(result <= 0, "removed command does not execute: " + input);
            equal(before, Files.readString(file(), StandardCharsets.UTF_8), "file unchanged after: " + input);
        }
        for (String name : new String[]{"cloudheight", "clouddistance", "cloudfade", "reload"})
            require(dispatcher.getRoot().getChild("skyboxblock").getChild(name) == null,
                    "no root node " + name);
    }

    // ── reload ───────────────────────────────────────────────────────────────────────────

    private static void reloadRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        require(succeeds(dispatcher, "skyboxblock env sky_color #112233", operator(new Capture())),
                "seed reload value");
        Properties properties = load(file());
        properties.setProperty("environment.visual/sky_color", "#ABCDEF");
        store(file(), properties);
        Capture reloadActor = operator();
        require(succeeds(dispatcher, "skyboxblock environment reload", operator(reloadActor)), "environment reload");
        require(text(reloadActor).contains("1"), "reload reports the override count: " + text(reloadActor));
        Capture query = player();
        require(succeeds(dispatcher, "skyboxblock env get sky_color", player(query)), "get after reload");
        equal("visual/sky_color = #ABCDEF", text(query), "reload picks up the manual edit");
    }

    // ── private sky time ─────────────────────────────────────────────────────────────────

    private static void timeRegression() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        resetStore();
        require(succeeds(dispatcher,"skyboxblock env set sky_color #112233",operator(new Capture())),"seed time baseline");
        String seeded = Files.readString(file(), StandardCharsets.UTF_8);
        for (String input : new String[]{"skyboxblock env time", "skyboxblock env time get",
                "skyboxblock env time reset", "skyboxblock env time set noon",
                "skyboxblock env time set 13000", "skyboxblock env time lock",
                "skyboxblock env time lock 18000", "skyboxblock env time lock sunrise",
                "skyboxblock env time unlock", "skyboxblock env time reset"}) {
            Capture actor = operator();
            require(succeeds(dispatcher, input, operator(actor)), "time command runs: " + input);
            require(!text(actor).isEmpty(), "time reports a description: " + input);
            require(!text(actor).contains("（"), "no parenthetical annotation: " + input);
        }
        // An invalid time is rejected before anything is stored.
        String afterValid = Files.readString(file(), StandardCharsets.UTF_8);
        for (String input : new String[]{"skyboxblock env time set 24000", "skyboxblock env time set -1",
                "skyboxblock env time set tomorrow", "skyboxblock env time lock 24000",
                "skyboxblock env time set 1000 extra", "skyboxblock env time set"}) {
            require(execute(dispatcher, input, operator(new Capture())) <= 0, "time rejects: " + input);
        }
        equal(afterValid, Files.readString(file(), StandardCharsets.UTF_8), "rejected times write nothing");
        // time never touches the environment keys that were seeded earlier in this run.
        require(Files.readString(file(), StandardCharsets.UTF_8).startsWith("#"),
                "store file still parses as properties");
        require(seeded != null, "seed captured");
        require(SkyTimeClock.DAY == 24000, "clock day length");
        // `set` must not clear an existing lock: only the frozen tick moves.
        Capture lockActor = operator();
        require(succeeds(dispatcher, "skyboxblock env time lock 18000", operator(lockActor)), "lock at midnight");
        equal("time = 18000 / locked", text(lockActor), "lock reports the locked tick");
        Capture setWhileLocked = operator();
        require(succeeds(dispatcher, "skyboxblock env time set noon", operator(setWhileLocked)), "set while locked");
        equal("time = 6000 / locked", text(setWhileLocked), "set keeps the lock and moves its tick");
        Capture queryLocked = player();
        require(succeeds(dispatcher, "skyboxblock env time get", player(queryLocked)), "player reads the locked clock");
        equal("time = 6000 / locked", text(queryLocked), "locked clock stays locked after set");
        Capture unlockActor = operator();
        require(succeeds(dispatcher, "skyboxblock env time unlock", operator(unlockActor)), "unlock after set");
        require(text(unlockActor).contains("/ running"), "unlock resumes running: " + text(unlockActor));
        require(text(unlockActor).startsWith("time = 6000"), "unlock resumes from the set tick: " + text(unlockActor));
        // Setting from running/auto keeps the running behaviour.
        Capture runningSet = operator();
        require(succeeds(dispatcher, "skyboxblock env time set night", operator(runningSet)), "set while running");
        require(text(runningSet).contains("/ running"), "set keeps running when not locked: " + text(runningSet));
    }

    // ── suggestions ──────────────────────────────────────────────────────────────────────

    /**
     * TAB comes from one suggestion provider on the shared attribute node, so each attribute is
     * offered in exactly the form matching the typed prefix — no 45 duplicate alias literals.
     */
    private static void suggestionRegression() {
        CommandDispatcher<CommandSourceStack> dispatcher = tree();
        CommandSourceStack source = operator(new Capture());
        for (String prefix : new String[]{"", "visual/", "minecraft:visual/"}) {
            for (String head : new String[]{"skyboxblock env get ", "skyboxblock env reset ",
                    "skyboxblock env set "}) {
                List<String> list = suggestions(dispatcher, source, head + prefix);
                equal(new LinkedHashSet<>(list).size(), list.size(), "no duplicate suggestion: " + head + prefix);
                for (SkyEnvironment.Attribute attribute : SkyEnvironment.attributes()) {
                    String expected = prefix + attribute.shortId();
                    require(list.contains(expected), "one form " + expected + " under " + prefix + ": " + list);
                    for (String other : new String[]{attribute.shortId(), attribute.id(),
                            "minecraft:" + attribute.id()})
                        if (!other.equals(expected))
                            require(!list.contains(other), "duplicate form " + other + " hidden under " + prefix);
                }
                int extras=head.endsWith(" reset ") && prefix.isEmpty() ? 1 : 0;
                if(extras==1) require(list.contains("all"),"reset all suggestion exists");
                equal(SkyEnvironment.attributes().size()+extras, list.size(),
                        "exactly one suggestion per attribute plus reset all under " + head + prefix);
            }
        }
        List<String> shortPrefix = suggestions(dispatcher, source, "skyboxblock env get cloud");
        require(shortPrefix.contains("cloud_height") && shortPrefix.contains("cloud_color"),
                "short prefix filtered: " + shortPrefix);
        require(shortPrefix.stream().noneMatch(value -> value.startsWith("visual/")),
                "short prefix has no visual/ duplicates: " + shortPrefix);
        // The greedy value argument carries no suggestions of its own; only time presets are offered.
        List<String> times = suggestions(dispatcher, source, "skyboxblock env time set ");
        require(times.contains("noon") && times.contains("midnight"), "time presets suggested: " + times);
        List<String> locked = suggestions(dispatcher, source, "skyboxblock env time lock s");
        require(locked.contains("sunset") && locked.contains("sunrise"), "lock presets filtered: " + locked);
    }

    // ── infra ────────────────────────────────────────────────────────────────────────────

    /** Test file used by the backend hook; a "restart" re-installs it, nothing else is invented. */
    private static Path configFile;

    private static Path file() {
        return configFile;
    }

    /** Simulates a server restart: the backend drops its store and re-reads the same file. */
    private static void restart() {
        SkyboxServerConfig.installTestFile(configFile);
    }

    /** The live snapshot the commands read, through the same store object they use. */
    private static SkyEnvironment environment() {
        return SkyboxServerConfig.forSource(operator(new Capture())).settings().environment();
    }

    /** The on-disk value of one property, or {@code null} when absent. */
    private static String findProperty(String name) throws Exception {
        Properties properties = load(file());
        String value = properties.getProperty(name);
        return value == null ? null : name + "=" + value;
    }

    /** Starts a case from an empty store file. */
    private static void resetStore() throws Exception {
        Files.deleteIfExists(configFile);
        restart();
    }

    private static Properties load(Path path) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(path)) { properties.load(in); }
        return properties;
    }

    /** Writes the file, then makes the backend re-read it (the store is file-backed). */
    private static void store(Path path, Properties properties) throws Exception {
        try (var out = Files.newOutputStream(path)) { properties.store(out, "server regression fixture"); }
        restart();
    }

    private static boolean succeeds(CommandDispatcher<CommandSourceStack> dispatcher, String input,
                                    CommandSourceStack source) {
        return execute(dispatcher, input, source) > 0;
    }

    private static int execute(CommandDispatcher<CommandSourceStack> dispatcher, String input,
                               CommandSourceStack source) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, source);
        if (!parsed.getExceptions().isEmpty()) {
            source.sendFailure(Component.literal(
                    "parse: " + parsed.getExceptions().values().iterator().next().getMessage()));
            return 0;
        }
        if (parsed.getReader().canRead()) {
            source.sendFailure(Component.literal("左余输入未解析：" + parsed.getReader().getRemaining()));
            return 0;
        }
        try {
            return dispatcher.execute(parsed);
        } catch (CommandSyntaxException | IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage() == null ? exception.toString() : exception.getMessage()));
            return 0;
        }
    }

    private static List<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                            CommandSourceStack source, String input) {
        Suggestions suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join();
        List<String> out = new ArrayList<>();
        suggestions.getList().forEach(suggestion -> out.add(suggestion.getText()));
        require(!out.isEmpty(), "there are suggestions for: " + input);
        return out;
    }

    private static void deleteQuietly(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
