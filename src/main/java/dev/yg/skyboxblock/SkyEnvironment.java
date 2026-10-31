package dev.yg.skyboxblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Environment-attribute registry for the vanilla sky window and the immutable value snapshot the
 * renderer reads.
 *
 * <p>Every attribute follows the vanilla "environment attribute" model: the value is either
 * <em>auto</em> (no override; the vanilla-derived R34.2 path decides) or an explicit override.
 * A snapshot never invents zeroes — {@link #EMPTY} answers every query with the caller's fallback,
 * so an untouched configuration renders exactly like R34.2.
 *
 * <p>Property key = {@code environment.} + full id, e.g. {@code environment.visual/sky_color=#7BA4FF}.
 * A JVM system property {@code -Dskyboxblock.environment.visual/sky_color=...} wins over the file.
 * Only explicit overrides are stored; an attribute reset to auto has no key at all.
 *
 * <p>Parsing/serialising is plain Java so it can be covered by the MC-free regression.
 */
public final class SkyEnvironment {
    /** Key prefix inside {@code config/skyboxblock.properties}. */
    public static final String KEY_PREFIX = "environment.";
    /** JVM property prefix. */
    public static final String JVM_PREFIX = "skyboxblock." + KEY_PREFIX;
    public static final String NAMESPACE = "minecraft:";
    public static final String GROUP = "visual/";
    /** Literal meaning "no override"; {@code environment.<id>=auto} in the file is equivalent to absent. */
    public static final String AUTO = "auto";

    /** Override of vanilla {@code visual/cloud_height} quoted by the wiki (absolute world Y). */
    public static final float DEFAULT_CLOUD_HEIGHT = 192.33F;
    /** Lowest representable positive float; distance attributes are strictly positive. */
    public static final float MIN_POSITIVE = Float.MIN_VALUE;
    /** Performance bound already enforced by the legacy cloud-distance commands. */
    public static final float MAX_CLOUD_FOG_END = 4096F;
    /** Fog distances must be strictly positive (vanilla divides by them); 0 is rejected. */
    public static final float MAX_FOG_DISTANCE = 32768F;
    public static final float MIN_FOG_START = -32768F, MAX_FOG_START = 32768F;
    public static final float MIN_CLOUD_HEIGHT = -32768F, MAX_CLOUD_HEIGHT = 32768F;

    /** Vanilla moon phases in vanilla ordinal order (0 = full moon). */
    public static final List<String> MOON_PHASES = List.of(
            "full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
            "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous");

    /** Registry of attribute kinds. Order is the registration/documentation order. */
    public enum Kind {
        /** `#RRGGBB` only — RGB, alpha implicitly opaque. */
        RGB,
        /** `#AARRGGBB` only — ARGB, signed int bits preserved verbatim. */
        ARGB,
        /** Plain finite float in [min, max]; no wrap-around. */
        NUMBER,
        /** Boolean literal {@code true}/{@code false} only. */
        FLAG,
        /** One of the eight {@link #MOON_PHASES} names, or an optional index 0..7. */
        PHASE
    }

    /** One registered environment attribute. */
    public record Attribute(String id, Kind kind, float min, float max, float defaultValue, String doc) {
        public String shortId() { return id.startsWith(GROUP) ? id.substring(GROUP.length()) : id; }
        /** True when the value must be strictly greater than zero. */
        public boolean positive() { return min == MIN_POSITIVE; }
        /** User-facing range text (Chinese). */
        public String range() {
            return ">0 .. " + formatNumber(max);
        }
        /** One-line help shown by {@code environment list} / {@code environment get}. */
        public String help() {
            return switch (kind) {
                case RGB -> "RGB 颜色： #RRGGBB（6 位，必须带 #）";
                case ARGB -> "ARGB 颜色： #AARRGGBB（8 位，必须带 #，保留 alpha 与符号位）";
                case FLAG -> "布尔： true / false";
                case PHASE -> "月相： " + String.join(" / ", MOON_PHASES) + "，或 0..7";
                case NUMBER -> "浮点：" + (positive() ? range() : formatNumber(min) + " .. " + formatNumber(max));
            };
        }
    }

    private static final Map<String, Attribute> REGISTRY = new LinkedHashMap<>();

    private static Attribute register(Attribute attribute) {
        REGISTRY.put(attribute.id(), attribute);
        return attribute;
    }

    public static final Attribute SKY_COLOR = register(new Attribute("visual/sky_color", Kind.RGB, 0, 0, 0,
            "天空颜色 RGB"));
    public static final Attribute FOG_COLOR = register(new Attribute("visual/fog_color", Kind.RGB, 0, 0, 0,
            "雾颜色 RGB"));
    public static final Attribute FOG_START_DISTANCE = register(new Attribute("visual/fog_start_distance", Kind.NUMBER,
            MIN_FOG_START, MAX_FOG_START, 0, "雾起始距离（可为负）"));
    public static final Attribute FOG_END_DISTANCE = register(new Attribute("visual/fog_end_distance", Kind.NUMBER,
            MIN_POSITIVE, MAX_FOG_DISTANCE, 0, "雾结束距离（严格 >0，不可为 0 或负）"));
    public static final Attribute SKY_FOG_END_DISTANCE = register(new Attribute(
            "visual/sky_fog_end_distance", Kind.NUMBER, MIN_POSITIVE, MAX_FOG_DISTANCE, 0,
            "天空雾结束距离（严格 >0，独立于 fog_end）"));
    public static final Attribute CLOUD_COLOR = register(new Attribute("visual/cloud_color", Kind.ARGB, 0, 0, 0,
            "云颜色 ARGB（#AARRGGBB，alpha=0 即隐藏云）"));
    public static final Attribute CLOUD_HEIGHT = register(new Attribute("visual/cloud_height", Kind.NUMBER,
            MIN_CLOUD_HEIGHT, MAX_CLOUD_HEIGHT, DEFAULT_CLOUD_HEIGHT,
            "云层高度（绝对最终世界 Y，不做额外偏移；Wiki 默认 192.33）"));
    public static final Attribute CLOUD_FOG_END_DISTANCE = register(new Attribute(
            "visual/cloud_fog_end_distance", Kind.NUMBER, MIN_POSITIVE, MAX_CLOUD_FOG_END, 0,
            "云雾结束距离（严格 >0，独立于公共 fog_end；沿用旧云距离指令的 4096 性能上限）"));
    public static final Attribute SUNRISE_SUNSET_COLOR = register(new Attribute(
            "visual/sunrise_sunset_color", Kind.ARGB, 0, 0, 0, "日出日落天光色 ARGB"));
    public static final Attribute SUN_ANGLE = register(new Attribute("visual/sun_angle", Kind.NUMBER,
            -Float.MAX_VALUE, Float.MAX_VALUE, 0, "太阳角度（有限值，不取模）"));
    public static final Attribute MOON_ANGLE = register(new Attribute("visual/moon_angle", Kind.NUMBER,
            -Float.MAX_VALUE, Float.MAX_VALUE, 0,
            "月亮角度（有限值，不取模）：0 = 正上方；原版默认与太阳相差 180°"));
    public static final Attribute STAR_ANGLE = register(new Attribute("visual/star_angle", Kind.NUMBER,
            -Float.MAX_VALUE, Float.MAX_VALUE, 0, "星空角度（有限值，不取模）"));
    public static final Attribute MOON_PHASE = register(new Attribute("visual/moon_phase", Kind.PHASE, 0, 7, 0,
            "月相 full_moon..waxing_gibbous / 0..7"));
    public static final Attribute STAR_BRIGHTNESS = register(new Attribute("visual/star_brightness", Kind.NUMBER,
            0, 1, 0, "星空亮度 0..1"));
    public static final Attribute HAS_SKY_OCCLUDER = register(new Attribute("visual/has_sky_occluder", Kind.FLAG,
            0, 0, 0, "天空是否有遮挡体（26.4 新增）"));

    /** All registered attributes in registration order. */
    public static List<Attribute> attributes() { return List.copyOf(REGISTRY.values()); }

    /** Registry lookup by full id, short id, or either prefixed with {@code minecraft:}. */
    public static Attribute attribute(String id) {
        if (id == null) return null;
        String text = id.trim();
        if (text.isEmpty()) return null;
        Attribute exact = REGISTRY.get(text);
        if (exact != null) return exact;
        String bare = text.startsWith(NAMESPACE) ? text.substring(NAMESPACE.length()) : text;
        Attribute unqualified = REGISTRY.get(bare);
        if (unqualified != null) return unqualified;
        Attribute grouped = REGISTRY.get(GROUP + bare);
        if (grouped != null) return grouped;
        if (bare.indexOf('/') < 0) {
            for (Attribute candidate : REGISTRY.values())
                if (candidate.shortId().equals(bare)) return candidate;
        }
        return null;
    }

    /** Canonical full id (e.g. {@code visual/sky_color}) for a loose name, or {@code null}. */
    public static String canonical(String id) {
        Attribute attribute = attribute(id);
        return attribute == null ? null : attribute.id();
    }

    /** Full ids accepting the already typed prefix, sorted; used for Brigadier suggestions. */
    public static List<String> suggestions(String prefix) {
        String text = prefix == null ? "" : prefix;
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Attribute attribute : REGISTRY.values()) {
            for (String form : new String[]{attribute.id(), NAMESPACE + attribute.id()}) {
                if (form.startsWith(text) && !out.contains(form)) out.add(form);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Short ids (for sub-argument suggestions and byte-precise node layouts). */
    public static List<String> shortIds() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Attribute attribute : REGISTRY.values()) out.add(attribute.shortId());
        return out;
    }

    // ── typed value handling ────────────────────────────────────────────────────────────────

    /** A stored override value. {@link #text()} is the canonical file form, e.g. {@code #AARRGGBB}. */
    public record Value(Attribute attribute, String text) {
        public float number() { return Float.parseFloat(text); }
        /** ARGB bits; the {@code #} prefix is skipped and the sign of the int is preserved. */
        public int color() { return (int) Long.parseLong(text.substring(1), 16); }
        public boolean flag() { return Boolean.parseBoolean(text); }
        public int moonPhase() { return MoonPhase.parse(text); }
    }

    /** Thrown for any value the attribute cannot represent; message is user-facing (Chinese). */
    public static final class InvalidValueException extends IllegalArgumentException {
        public InvalidValueException(String message) { super(message); }
    }


    /**
     * Parses and validates a raw token for one attribute.
     *
     * @throws InvalidValueException when the token is unknown, malformed, non-finite or out of range
     */
    public static Value parse(Attribute attribute, String raw) {
        if (attribute == null) throw new InvalidValueException("未知的环境属性");
        if (raw == null) throw new InvalidValueException(attribute.id() + " 缺少数值");
        String text = raw.trim();
        if (text.isEmpty()) throw new InvalidValueException(attribute.id() + " 缺少数值");
        if (text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0)
            throw new InvalidValueException(attribute.id() + " 数值不能包含空白（多余 token）：" + raw.trim());
        switch (attribute.kind()) {
            case RGB, ARGB -> { return new Value(attribute, canonicalColor(attribute, text)); }
            case NUMBER -> { return new Value(attribute, canonicalNumber(attribute, text)); }
            case FLAG -> { return new Value(attribute, canonicalFlag(attribute, text)); }
            case PHASE -> { return new Value(attribute, MOON_PHASES.get(canonicalPhase(attribute, text))); }
            default -> throw new InvalidValueException("未支持的环境属性类型");
        }
    }

    /** Parses one colour token. Only the {@code #RRGGBB}/{@code #AARRGGBB} form is accepted. */
    private static String canonicalColor(Attribute attribute, String text) {
        if (text.startsWith("0x") || text.startsWith("0X"))
            throw new InvalidValueException(attribute.id() + " 只接受 # 开头的十六进制颜色码，收到 "
                    + text + "（请写成 " + expectedSample(attribute) + "）");
        String digits;
        if (text.startsWith("#")) digits = text.substring(1);
        else throw new InvalidValueException(attribute.id() + " 只接受 # 开头的十六进制颜色码，收到 "
                + text + "（例如 " + expectedSample(attribute) + "）");
        int expected = attribute.kind() == Kind.ARGB ? 8 : 6;
        if (digits.length() != expected)
            throw new InvalidValueException(attribute.id() + " 需要 # 加 " + expected + " 位十六进制，收到 "
                    + digits.length() + " 位（例如 " + expectedSample(attribute) + "）");
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0)
                throw new InvalidValueException(attribute.id() + " 含非十六进制字符：" + digits.charAt(i));
        }
        return "#" + digits.toUpperCase(Locale.ROOT);
    }

    /** A canonical example in the only accepted colour form. */
    private static String expectedSample(Attribute attribute) {
        return attribute.kind() == Kind.ARGB ? "#FFAABBCC" : "#AABBCC";
    }

    private static String canonicalNumber(Attribute attribute, String text) {
        float value;
        try { value = Float.parseFloat(text); }
        catch (NumberFormatException exception) {
            throw new InvalidValueException(attribute.id() + " 不是合法数字：" + text);
        }
        if (!Float.isFinite(value))
            throw new InvalidValueException(attribute.id() + " 必须是有限数字（拒绝 NaN / Infinity）");
        float low = attribute.min(), high = attribute.max();
        if (value < low || value > high)
            throw new InvalidValueException(attribute.id() + " 超出范围 " + describeRange(attribute)
                    + "，收到 " + formatNumber(value));
        if (attribute.positive() && !(value > 0F))
            throw new InvalidValueException(attribute.id() + " 必须严格大于 0（距离属性不允许 0 或负数），收到 "
                    + formatNumber(value));
        return formatNumber(value);
    }

    private static String describeRange(Attribute attribute) {
        if (attribute.positive()) return attribute.range();
        return formatBound(attribute.min()) + " .. " + formatBound(attribute.max());
    }

    private static String canonicalFlag(Attribute attribute, String text) {
        if (text.equalsIgnoreCase("true")) return "true";
        if (text.equalsIgnoreCase("false")) return "false";
        throw new InvalidValueException(attribute.id() + " 只接受 true 或 false，拒绝：" + text);
    }

    private static int canonicalPhase(Attribute attribute, String text) {
        int index;
        if (text.matches("\\d{1,2}")) {
            index = Integer.parseInt(text);
            if (index > 7)
                throw new InvalidValueException(attribute.id() + " 月相序号须为 0..7，收到 " + text);
        } else {
            String name = text.toLowerCase(Locale.ROOT).replace('-', '_');
            index = MOON_PHASES.indexOf(name);
            if (index < 0)
                throw new InvalidValueException(attribute.id() + " 未知月相：" + text
                        + "（可选 " + String.join(" / ", MOON_PHASES) + "，或 0..7）");
        }
        return index;
    }

    /** Canonical text form of a finite float: integral values print without a fractional part. */
    public static String formatNumber(float value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0E9F) return Integer.toString((int) value);
        return Float.toString(value);
    }

    private static String formatBound(float value) {
        if (value == -Float.MAX_VALUE) return "-∞";
        if (value == Float.MAX_VALUE) return "+∞";
        return formatNumber(value);
    }

    /** Vanilla moon-phase name (0..7) ↔ index helper kept next to the registry. */
    public static final class MoonPhase {
        private MoonPhase() { }
        public static String name(int index) { return MOON_PHASES.get(Math.floorMod(index, 8)); }
        public static int parse(String text) {
            if (text.matches("\\d+")) return Math.floorMod(Integer.parseInt(text), 8);
            int index = MOON_PHASES.indexOf(text.toLowerCase(Locale.ROOT).replace('-', '_'));
            if (index < 0) throw new InvalidValueException("未知月相：" + text);
            return index;
        }
    }

    // ── snapshot ────────────────────────────────────────────────────────────────────────────

    /** The all-auto snapshot: nothing overridden, every query returns the caller's fallback. */
    public static final SkyEnvironment EMPTY = new SkyEnvironment(Map.of());

    private final Map<String, Value> values;

    private SkyEnvironment(Map<String, Value> values) { this.values = values; }

    /**
     * Builds a snapshot from config entries. Keys not under {@link #KEY_PREFIX} are ignored; an
     * entry whose value is {@code auto} or fails validation is dropped (auto) and reported to
     * {@code warnings}, which never throws.
     *
     * <p>This method is pure JDK: it never touches the mod's static initialisation, so it is safe
     * to call from a Minecraft-free regression. Callers pass their own logger sink.
     */
    public static SkyEnvironment fromEntries(Map<String, String> entries,
                                             java.util.function.Consumer<String> warnings) {
        Map<String, Value> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : canonicalEntries(entries).entrySet()) {
            String id = entry.getKey();
            Attribute attribute = REGISTRY.get(id);
            String raw = entry.getValue();
            if (attribute == null) {
                warnings.accept("未知环境属性 " + KEY_PREFIX + id + "，已忽略");
                continue;
            }
            if (raw == null || raw.trim().equalsIgnoreCase(AUTO)) continue;
            try { parsed.put(attribute.id(), parse(attribute, raw)); }
            catch (RuntimeException exception) {
                warnings.accept("环境属性 " + KEY_PREFIX + attribute.id() + " 无效（" + exception.getMessage()
                        + "），按 auto 处理");
            }
        }
        return parsed.isEmpty() ? EMPTY : new SkyEnvironment(Collections.unmodifiableMap(parsed));
    }

    /** As {@link #fromEntries(Map, java.util.function.Consumer)} but silent. */
    public static SkyEnvironment fromEntries(Map<String, String> entries) {
        return fromEntries(entries, ignored -> { });
    }

    /** File key for an attribute. */
    public static String key(Attribute attribute) { return KEY_PREFIX + attribute.id(); }

    /**
     * Collapses aliased keys onto one canonical entry per attribute. Accepts
     * {@code environment.<short>}, {@code environment.visual/<x>} and
     * {@code environment.minecraft:visual/<x>}; ties are resolved deterministically: the canonical
     * {@code visual/...} key wins, then {@code minecraft:visual/...}, then a short id, and equal
     * ranks fall back to the lexicographically smallest key. The result never keeps two entries for
     * one attribute and never re-orders attributes, so a file round-trip is stable.
     *
     * @param entries raw key → value map (non-environment keys are ignored here)
     * @return canonical id → value; unknown environment keys are kept verbatim for warnings
     */
    public static Map<String, String> canonicalEntries(Map<String, String> entries) {
        Map<String, String> chosen = new LinkedHashMap<>();
        Map<String, Integer> rank = new LinkedHashMap<>();
        Map<String, String> chosenKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(KEY_PREFIX)) continue;
            String suffix = key.substring(KEY_PREFIX.length());
            Attribute attribute = attribute(suffix);
            if (attribute == null) {
                // Keep unknown keys visible (rank -1) so the caller can warn once per key.
                if (!chosen.containsKey(key)) { chosen.put(key, entry.getValue()); rank.put(key, -1); }
                continue;
            }
            int candidate = aliasRank(suffix, attribute);
            Integer best = rank.get(attribute.id());
            if (best == null || candidate > best
                    || (candidate == best && key.compareTo(chosenKey.get(attribute.id())) < 0)) {
                chosen.put(attribute.id(), entry.getValue());
                rank.put(attribute.id(), candidate);
                chosenKey.put(attribute.id(), key);
            }
        }
        return chosen;
    }

    /** Higher wins when two keys name the same attribute. */
    private static int aliasRank(String suffix, Attribute attribute) {
        if (suffix.equals(attribute.id())) return 3;
        if (suffix.equals(attribute.shortId())) return 1;
        if (suffix.equals(NAMESPACE + attribute.id())) return 2;
        return 0;
    }

    /** Every file key that currently names {@code attribute} inside {@code properties}. */
    public static List<String> aliasedKeys(java.util.Set<String> propertyNames, Attribute attribute) {
        if (attribute == null) return List.of();
        List<String> keys = new ArrayList<>();
        for (String name : propertyNames)
            if (name != null && name.startsWith(KEY_PREFIX) && attribute.equals(resolve(name))) keys.add(name);
        return keys;
    }

    /** The attribute a raw {@code environment.*} key names, or {@code null}. */
    private static Attribute resolve(String environmentKey) {
        return attribute(environmentKey.substring(KEY_PREFIX.length()));
    }

    /** True when the attribute is explicitly overridden in this snapshot. */
    public boolean overridden(String shortId) { return has(shortId); }

    /** True when the attribute carries an explicit override. */
    public boolean has(String shortId) {
        Attribute attribute = attribute(shortId);
        return attribute != null && values.containsKey(attribute.id());
    }

    /** Stored value of an overridden attribute, or {@code null}. */
    public Value value(String shortId) {
        Attribute attribute = attribute(shortId);
        return attribute == null ? null : values.get(attribute.id());
    }

    /**
     * Numeric value of the attribute, or {@code fallback} when it is auto.
     *
     * @throws InvalidValueException when the attribute is not numeric
     */
    public float number(String shortId, float fallback) {
        Value value = value(shortId);
        if (value == null) return fallback;
        if (value.attribute().kind() == Kind.NUMBER) return value.number();
        if (value.attribute().kind() == Kind.PHASE) return value.moonPhase();
        throw new InvalidValueException(value.attribute().id() + " 不是数值属性");
    }

    /**
     * Colour value (ARGB, signed bits preserved) of the attribute, or {@code fallback} when auto.
     *
     * @throws InvalidValueException when the attribute is not RGB/ARGB
     */
    public int color(String shortId, int fallback) {
        Value value = value(shortId);
        if (value == null) return fallback;
        Kind kind = value.attribute().kind();
        if (kind == Kind.ARGB) return value.color();
        if (kind == Kind.RGB) return 0xFF000000 | value.color();
        throw new InvalidValueException(value.attribute().id() + " 不是颜色属性");
    }

    /** Boolean value of the attribute, or {@code fallback} when auto. */
    public boolean flag(String shortId, boolean fallback) {
        Value value = value(shortId);
        if (value == null) return fallback;
        if (value.attribute().kind() == Kind.FLAG) return value.flag();
        throw new InvalidValueException(value.attribute().id() + " 不是布尔属性");
    }

    /** Moon phase index (0..7) of {@code visual/moon_phase}, or {@code fallback} when auto. */
    public int moonPhase(int fallback) {
        Value value = value(MOON_PHASE.id());
        return value == null ? fallback : value.moonPhase();
    }

    /** Overridden attributes, in file order. */
    public Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Value value : values.values()) out.put(key(value.attribute()), value.text());
        return Collections.unmodifiableMap(out);
    }

    /** All overrides as `id=value` pairs for user feedback. */
    public List<String> describe() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : overrides().entrySet())
            out.add(entry.getKey().substring(KEY_PREFIX.length()) + "=" + entry.getValue());
        return out;
    }

    /** Effective text for one attribute: the stored value, or {@code auto}. */
    public String effective(String shortId) {
        Value value = value(shortId);
        return value == null ? "auto" : value.text();
    }

    @Override public String toString() { return "SkyEnvironment" + describe(); }
}
