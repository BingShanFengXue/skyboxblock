package dev.yg.skyboxblock.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Server-authoritative {@code /skyboxblock} tree. Environment attributes and the private sky time
 * are stored once for the whole server world, so a change applies to every player; the backend
 * broadcasts the result, this class never sends its own sync packet.
 *
 * <p>Deliberately free of client classes: the only non-vanilla dependency is the Minecraft-free
 * attribute registry {@link SkyEnvironment}. The time model is {@link SkyTimeClock} (pure JDK).
 *
 * <p>The tree is deliberately <em>serialisable</em>: the command tree travels to clients in
 * {@code ClientboundCommandsPacket}, which only understands vanilla argument types. So there is
 * exactly one {@code attribute} argument node built on {@link ResourceLocationArgument#id()} (a
 * built-in type), and every value uses the built-in greedy string. A custom literal-per-attribute
 * tree or a custom {@code ArgumentType} would be dropped or fail to serialise for real players.
 * <pre>
 * /skyboxblock                                     short usage
 * /skyboxblock environment list
 * /skyboxblock environment get &lt;attribute&gt;
 * /skyboxblock environment set &lt;attribute&gt; &lt;value&gt;
 * /skyboxblock environment reset &lt;attribute&gt; | reset all
 * /skyboxblock environment reload
 * /skyboxblock environment time [get] | set &lt;value&gt; | lock [value] | unlock | reset
 * </pre>
 * Reads ({@code list}, {@code get}, a bare attribute, {@code time}, {@code time get}) are open to
 * every player; every mutation, including the shortcut routes {@code <attribute> <value>} and
 * {@code <attribute> reset}, requires permission level {@link #MUTATION_LEVEL}.
 *
 * <p>{@code <attribute>} accepts the three id forms ({@code sky_color}, {@code visual/sky_color},
 * {@code minecraft:visual/sky_color}) because {@link SkyEnvironment#attribute(String)} resolves all
 * of them, while the TAB suggestion lists only the form matching what the player typed.
 */
public final class SkyboxServerCommands {
    private SkyboxServerCommands() { }

    /** Group node names; {@code env} is an accepted alias of {@code environment}. */
    public static final String ENVIRONMENT = "environment", ENVIRONMENT_ALIAS = "env";
    /** The single attribute argument name (a vanilla resource location). */
    public static final String ATTRIBUTE = "attribute";
    /** Value argument name for attributes and for the private sky time. */
    public static final String VALUE = "value";
    /** Sub-group holding the private sky time controls. */
    public static final String TIME = "time";
    /** Required permission level for every state-changing node. */
    public static final int MUTATION_LEVEL = 2;
    /** Id prefix that selects the {@code visual/} completion form. */
    static final String ID_GROUP = SkyEnvironment.GROUP;

    /** Registers the tree through the real v2 command callback (three-argument). */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> buildTree(dispatcher));
    }

    /**
     * Builds the whole {@code /skyboxblock} tree on a server dispatcher.
     *
     * @return the same dispatcher, so a real-Brigadier regression can parse and execute input
     */
    public static CommandDispatcher<CommandSourceStack> buildTree(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("skyboxblock")
                .executes(context -> usage(context.getSource()))
                .then(environmentNode(ENVIRONMENT))
                .then(environmentNode(ENVIRONMENT_ALIAS)));
        return dispatcher;
    }

    // ── tree ──────────────────────────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> environmentNode(String name) {
        ArgumentBuilder<CommandSourceStack, ?> attribute = attributeNode(
                context -> get(context.getSource(), attribute(context)))
                .then(Commands.literal("reset")
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> reset(context.getSource(), attribute(context))))
                .then(valueNode()
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> set(context.getSource(), attribute(context), value(context))));
        return Commands.literal(name)
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("reload")
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("reset")
                        .executes(context -> list(context.getSource()))
                        .then(Commands.literal("all")
                                .requires(SkyboxServerCommands::canMutate)
                                .executes(context -> resetAll(context.getSource())))
                        .then(attributeNode(context -> reset(context.getSource(), attribute(context)))
                                .requires(SkyboxServerCommands::canMutate)))
                .then(Commands.literal("get").then(attributeNode(
                        context -> get(context.getSource(), attribute(context)))))
                // `set <id>` without a value reports the usage instead of silently doing nothing.
                .then(Commands.literal("set")
                        .requires(SkyboxServerCommands::canMutate)
                        .then(attributeNode(context -> missingValue(context.getSource(), attribute(context)))
                                .then(valueNode()
                                        .requires(SkyboxServerCommands::canMutate)
                                        .executes(context -> set(context.getSource(), attribute(context),
                                                value(context))))))
                .then(attribute)
                .then(timeNode());
    }

    private static boolean canMutate(CommandSourceStack source) {
        return source.hasPermission(MUTATION_LEVEL);
    }

    /**
     * The single {@code <attribute>} argument. The vanilla resource-location type keeps the tree
     * serialisable to clients and already accepts short, grouped and namespaced ids; the suggestion
     * provider lists one form per attribute and is safely swapped to {@code ask_server} for players.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> attributeNode(AttributeCommand command) {
        return Commands.argument(ATTRIBUTE, ResourceLocationArgument.id())
                .suggests(SkyboxServerCommands::suggestAttributes)
                .executes(context -> command.run(context));
    }

    /** Value node: built-in greedy string; {@link SkyEnvironment#parse} validates execution-side. */
    private static ArgumentBuilder<CommandSourceStack, ?> valueNode() {
        return Commands.argument(VALUE, StringArgumentType.greedyString());
    }
    @FunctionalInterface
    private interface AttributeCommand { int run(CommandContext<CommandSourceStack> context); }

    // ── attribute resolution and suggestions ─────────────────────────────────────────────

    /** The parsed attribute of this context, or {@code null} when it is unknown. */
    static SkyEnvironment.Attribute attribute(CommandContext<CommandSourceStack> context) {
        return SkyEnvironment.attribute(ResourceLocationArgument.getId(context, ATTRIBUTE).toString());
    }

    /** The raw value text of this context. */
    static String value(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, VALUE);
    }

    /**
     * Suggests the single id form matching the typed prefix: short ids normally, {@code visual/...}
     * once {@code visual/} is typed, {@code minecraft:visual/...} once the namespace is typed.
     */
    private static CompletableFuture<Suggestions> suggestAttributes(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        for (SkyEnvironment.Attribute attribute : SkyEnvironment.attributes()) {
            String form = offeredForm(attribute, typed);
            if (form.startsWith(typed)) builder.suggest(form);
        }
        return builder.buildFuture();
    }

    /** The single id form offered for one attribute under the already typed {@code prefix}. */
    static String offeredForm(SkyEnvironment.Attribute attribute, String prefix) {
        String typed = prefix == null ? "" : prefix;
        if (typed.startsWith(SkyEnvironment.NAMESPACE)) return SkyEnvironment.NAMESPACE + attribute.id();
        if (typed.startsWith(ID_GROUP)) return attribute.id();
        return attribute.shortId();
    }

    // ── /skyboxblock environment time ─────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<CommandSourceStack> timeNode() {
        return Commands.literal(TIME)
                .executes(context -> timeGet(context.getSource()))
                .then(Commands.literal("get").executes(context -> timeGet(context.getSource())))
                .then(Commands.literal("set")
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> timeUsage(context.getSource()))
                        .then(Commands.argument(VALUE, StringArgumentType.greedyString())
                                .suggests(SkyboxServerCommands::suggestTimes)
                                .executes(context -> timeSet(context.getSource(), value(context)))))
                .then(Commands.literal("lock")
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> timeLock(context.getSource(), null))
                        .then(Commands.argument(VALUE, StringArgumentType.greedyString())
                                .suggests(SkyboxServerCommands::suggestTimes)
                                .executes(context -> timeLock(context.getSource(), value(context)))))
                .then(Commands.literal("unlock")
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> timeUnlock(context.getSource())))
                .then(Commands.literal("reset")
                        .requires(SkyboxServerCommands::canMutate)
                        .executes(context -> timeReset(context.getSource())));
    }

    private static CompletableFuture<Suggestions> suggestTimes(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        for (String preset : TIME_PRESET_NAMES) if (preset.startsWith(typed)) builder.suggest(preset);
        return builder.buildFuture();
    }

    private static int timeGet(CommandSourceStack source) {
        try {
            return success(source, describeTime(source, store(source)));
        } catch (RuntimeException exception) {
            return failure(source, "读取时间失败：" + message(exception));
        }
    }

    private static int timeSet(CommandSourceStack source, String raw) {
        try {
            final int ticks=timeTicks(raw);
            return timeChange(source, "设置时间失败：", store -> store.setTime(ticks, gameTime(source)));
        } catch(IllegalArgumentException invalid) { return failure(source,"设置时间失败："+message(invalid)); }
    }

    private static int timeLock(CommandSourceStack source, String raw) {
        try {
            final Integer ticks=raw == null ? null : Integer.valueOf(timeTicks(raw));
            return timeChange(source, "锁定时间失败：",
                    store -> store.lockTime(ticks, gameTime(source), automaticTicks(source)));
        } catch(IllegalArgumentException invalid) { return failure(source,"锁定时间失败："+message(invalid)); }
    }

    private static int timeUnlock(CommandSourceStack source) {
        return timeChange(source, "解除时间锁定失败：", store -> store.unlockTime(gameTime(source)));
    }

    private static int timeReset(CommandSourceStack source) {
        return timeChange(source, "重置时间设置失败：", SkyboxServerConfig.Store::resetTime);
    }

    private static int timeUsage(CommandSourceStack source) {
        return failure(source, "用法：/skyboxblock environment time set <0..23999 或 " + TIME_PRESET_HELP + ">");
    }

    /** Runs one time mutation, then reports the short effective description. */
    private static int timeChange(CommandSourceStack source, String failurePrefix, TimeWrite write) {
        try {
            SkyboxServerConfig.Store store = store(source);
            write.run(store);
            return success(source, describeTime(source, store));
        } catch (IOException | RuntimeException exception) {
            return failure(source, failurePrefix + message(exception));
        }
    }

    @FunctionalInterface
    private interface TimeWrite { void run(SkyboxServerConfig.Store store) throws IOException; }

    /** Parses a tick count or one of the fixed presets; range errors are user-facing (Chinese). */
    static int timeTicks(String raw) {
        String text = raw == null ? "" : raw.trim();
        Integer preset = TIME_PRESETS.get(text.toLowerCase(Locale.ROOT));
        if (preset != null) return preset;
        int ticks;
        try { ticks = Integer.parseInt(text); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("未知时刻：" + text + "（可用 " + TIME_PRESET_HELP + "，或 0..23999）");
        }
        if (ticks < 0 || ticks >= SkyTimeClock.DAY)
            throw new IllegalArgumentException("时刻须为 0..23999，收到 " + ticks);
        return ticks;
    }

    /** The fixed time presets, in command order. */
    static final List<String> TIME_PRESET_NAMES = List.of("day", "noon", "night", "midnight", "sunrise", "sunset");
    static final Map<String, Integer> TIME_PRESETS = Map.of(
            "day", 1000, "noon", 6000, "night", 13000, "midnight", 18000, "sunrise", 0, "sunset", 12000);
    static final String TIME_PRESET_HELP = String.join(" / ", TIME_PRESET_NAMES);

    // ── feedback ──────────────────────────────────────────────────────────────────────────

    private static int success(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int failure(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
        return 0;
    }

    private static String message(Throwable failure) {
        String text = failure.getMessage();
        return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
    }

    private static int usage(CommandSourceStack source) {
        success(source, "/skyboxblock environment|env list · get <attribute> · set <attribute> <value>"
                + " · reset <attribute> · reset all · reload · time get|set|lock|unlock|reset"
                + "（修改需权限等级 " + MUTATION_LEVEL + "）");
        return success(source, "服务器统一生效，所有玩家同步；查询无需权限");
    }

    private static int list(CommandSourceStack source) {
        SkyEnvironment environment;
        // Read-only path: forSource/settings() throw unchecked exceptions only, never IOException.
        try { environment = store(source).settings().environment(); }
        catch (RuntimeException exception) {
            return failure(source, "读取配置失败：" + message(exception));
        }
        success(source, "原版天空盒环境属性（服务器统一，共 " + SkyEnvironment.attributes().size()
                + " 项；auto = 未覆盖）");
        for (SkyEnvironment.Attribute attribute : SkyEnvironment.attributes())
            success(source, "  " + attribute.id() + " = " + effective(environment, attribute));
        return success(source, "用法 /skyboxblock environment get|set|reset <attribute> [value]；"
                + "reset all 全部 auto；reload 重读配置");
    }

    /** One line: {@code id = value}. */
    private static int get(CommandSourceStack source, SkyEnvironment.Attribute attribute) {
        if (attribute == null) return failure(source, "未知的环境属性");
        // Read-only path: the snapshot accessors never throw IOException.
        try {
            return success(source, attribute.id() + " = " + effective(store(source).settings().environment(), attribute));
        } catch (RuntimeException exception) {
            return failure(source, "读取配置失败：" + message(exception));
        }
    }

    private static int missingValue(CommandSourceStack source, SkyEnvironment.Attribute attribute) {
        return failure(source, "用法：/skyboxblock environment set " + attribute.id() + " <value>");
    }

    private static int set(CommandSourceStack source, SkyEnvironment.Attribute attribute, String raw) {
        if (attribute == null) return failure(source, "未知的环境属性，未修改设置");
        if (raw != null && raw.trim().equalsIgnoreCase(SkyEnvironment.AUTO)) return reset(source, attribute);
        String text;
        try { text = SkyEnvironment.parse(attribute, raw).text(); }
        catch (IllegalArgumentException exception) {
            return failure(source, "设置失败：" + exception.getMessage() + "（设置未改动）");
        }
        try {
            SkyboxServerConfig.Store store = store(source);
            store.setEnvironment(attribute.id(), text);
            return success(source, attribute.id() + " = " + effective(store.settings().environment(), attribute));
        } catch (IOException | RuntimeException exception) {
            return failure(source, "环境属性保存失败：" + message(exception));
        }
    }

    private static int reset(CommandSourceStack source, SkyEnvironment.Attribute attribute) {
        if (attribute == null) return failure(source, "未知的环境属性，未修改设置");
        try {
            SkyboxServerConfig.Store store = store(source);
            store.resetEnvironment(attribute.id());
            return success(source, attribute.id() + " = " + effective(store.settings().environment(), attribute));
        } catch (IOException | RuntimeException exception) {
            return failure(source, "环境属性重置失败：" + message(exception));
        }
    }

    private static int resetAll(CommandSourceStack source) {
        try {
            store(source).resetAll();
        } catch (IOException | RuntimeException exception) {
            return failure(source, "环境属性全部重置失败：" + message(exception));
        }
        return success(source, "全部环境属性 = auto");
    }

    private static int reload(CommandSourceStack source) {
        try {
            SkyboxServerConfig.Store store = store(source);
            store.reload();
            return success(source, "已重读配置；环境覆盖 " + store.settings().environment().overrides().size() + " 项");
        } catch (IOException | RuntimeException exception) {
            return failure(source, "重读配置失败：" + message(exception));
        }
    }

    // ── store access and read-only views ─────────────────────────────────────────────────

    /** The per-server store for this source (a test file store in class-path regressions). */
    private static SkyboxServerConfig.Store store(CommandSourceStack source) {
        return SkyboxServerConfig.forSource(source);
    }

    /** Effective text for one attribute: the stored override, else {@code auto}. */
    private static String effective(SkyEnvironment environment, SkyEnvironment.Attribute attribute) {
        return environment.has(attribute.id()) ? environment.effective(attribute.id()) : SkyEnvironment.AUTO;
    }

    /** Server game time in ticks; {@link SkyboxServerConfig} answers 0 outside a live server. */
    static long gameTime(CommandSourceStack source) { return SkyboxServerConfig.gameTime(source); }

    /** The automatic (vanilla-following) time in ticks; 0 outside a live server. */
    static double automaticTicks(CommandSourceStack source) { return SkyboxServerConfig.automaticTicks(source); }

    /** The one short time description the backend produces for this moment. */
    private static String describeTime(CommandSourceStack source, SkyboxServerConfig.Store store) {
        String text = store.timeDescription(gameTime(source), automaticTicks(source));
        return text == null ? "" : text;
    }
}
