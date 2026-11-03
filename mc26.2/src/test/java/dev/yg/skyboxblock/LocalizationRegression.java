package dev.yg.skyboxblock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 26.2 本地化回归：只做纯文本层面的断言，不依赖 MC 运行时。
 *
 * <p>为什么需要它：26.2 起 {@code BlockItem} 不再覆写 {@code getDescriptionId()}，
 * 物品名改为 {@code Item.Properties} 里的 {@code descriptionId}（默认前缀 {@code item.}）。
 * 一旦 {@code SkyboxBlockMod#itemProperties} 漏掉 {@code useBlockDescriptionPrefix()}，
 * 游戏里只会表现为「创造栏显示原始键名」这种软故障，编译、单测、资源校验都不会报错。
 * 本类用源码断言把这条约定钉死，并顺带校验资源键与 1.21.1 参照实现逐条一致。
 *
 * <p>运行方式（本工作区不使用 JUnit，走 {@code main} + 退出码；只需要 JDK，不需要 MC classpath）：
 * <pre>
 *   javac -d out src/test/java/dev/yg/skyboxblock/LocalizationRegression.java
 *   java -cp out dev.yg.skyboxblock.LocalizationRegression &lt;项目根&gt; &lt;1.21.1 参照项目根&gt;
 * </pre>
 * 只给一个参数时，跳过与参照实现的对照。
 */
public final class LocalizationRegression {
    /**
     * 模组 id。刻意写死而不引用 {@link SkyboxBlockMod#MOD_ID}：
     * 那需要 MC + Fabric 的完整 classpath，本回归要能在「只有 JDK」的环境下独立跑。
     * {@link #requireModIdMatchesSource(Path)} 会比对源码里的常量，防止这里的值与实现漂移。
     */
    static final String MOD_ID = "skyboxblock";

    /** 每个注册的方块都要有的一条 lang 键前缀。 */
    private static final String BLOCK_PREFIX = "block." + MOD_ID + ".";

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path reference = args.length > 1 ? Path.of(args[1]).toAbsolutePath().normalize() : null;
        Path langDir = project.resolve("src/main/resources/assets/skyboxblock/lang");
        Path modSource = project.resolve("src/main/java/dev/yg/skyboxblock/SkyboxBlockMod.java");
        Path styleSource = project.resolve("src/main/java/dev/yg/skyboxblock/SkyStyle.java");

        System.out.println("[lang-regression] project   = " + project);
        System.out.println("[lang-regression] reference = " + (reference == null ? "(skipped)" : reference));

        requireModIdMatchesSource(modSource);
        requireSourceUsesBlockPrefix(modSource);
        for (String code : List.of("en_us", "zh_cn")) {
            Path file = langDir.resolve(code + ".json");
            Map<String, String> entries = readFlatJson(file);
            requireBlockKeysCoverAllStyles(code, entries, styleSource);
            requireNoDuplicateItemKeys(code, entries);
            requireNonEmptyValues(code, entries);
            if (reference != null) {
                requireParityWithReference(code, entries, reference.resolve("src/main/resources/assets/skyboxblock/lang/" + code + ".json"));
            }
        }
        requireLangFilesAgree(langDir);

        if (!FAILURES.isEmpty()) {
            System.out.println("[lang-regression] FAILED (" + FAILURES.size() + ")");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("[lang-regression] PASS");
    }

    // ---------------------------------------------------------------- 断言

    /** 守住本类里写死的 {@link #MOD_ID} 与 {@code SkyboxBlockMod.MOD_ID} 常量一致。 */
    private static void requireModIdMatchesSource(Path modSource) throws IOException {
        if (!Files.isRegularFile(modSource)) {
            return; // requireSourceUsesBlockPrefix 会报缺失
        }
        String source = Files.readString(modSource, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("String\\s+MOD_ID\\s*=\\s*\"([^\"]+)\"").matcher(source);
        if (!matcher.find()) {
            FAILURES.add("SkyboxBlockMod 里找不到 MOD_ID 常量声明");
            return;
        }
        String declared = matcher.group(1);
        if (!MOD_ID.equals(declared)) {
            FAILURES.add("本回归写死 MOD_ID=\"" + MOD_ID + "\"，与源码里的 \"" + declared + "\" 不一致");
        }
    }

    /**
     * 根因守卫：{@code itemProperties(...)} 必须把描述键前缀拨回 {@code block}。
     *
     * <p>做法是在 {@code itemProperties} 方法体内搜索 {@code useBlockDescriptionPrefix()}，
     * 并确认没有任何 {@code useItemDescriptionPrefix()} 把它改回去。
     */
    private static void requireSourceUsesBlockPrefix(Path modSource) throws IOException {
        if (!Files.isRegularFile(modSource)) {
            FAILURES.add("找不到 " + modSource);
            return;
        }
        String source = Files.readString(modSource, StandardCharsets.UTF_8);
        int from = source.indexOf("Item.Properties itemProperties(");
        if (from < 0) {
            FAILURES.add("SkyboxBlockMod 里找不到 itemProperties(Identifier)（方法被改名或删除，本回归失效）");
            return;
        }
        int open = source.indexOf('{', from);
        int end = open < 0 ? -1 : matchingBrace(source, open);
        if (end < 0) {
            FAILURES.add("itemProperties(Identifier) 的方法体无法解析");
            return;
        }
        String body = source.substring(open, end + 1);
        // 去掉注释再判断，避免注释里的示例把断言喂饱。
        String code = stripComments(body);
        if (!code.contains("useBlockDescriptionPrefix()")) {
            FAILURES.add("itemProperties(Identifier) 缺少 useBlockDescriptionPrefix()："
                    + "26.2 的 BlockItem 不再借用方块键，物品名会去查不存在的 item.* 键");
        }
        if (code.contains("useItemDescriptionPrefix()")) {
            FAILURES.add("itemProperties(Identifier) 又把前缀改回 item.：会覆盖 useBlockDescriptionPrefix()");
        }
        if (!code.contains("setId(")) {
            FAILURES.add("itemProperties(Identifier) 缺少 setId(ResourceKey)：26.2 构造期会抛 Item id not set");
        }
    }

    /**
     * 每个注册的方块都必须在 lang 里有对应的 {@code block.skyboxblock.<path>}。
     *
     * <p>方块 id 直接从 {@code SkyStyle.java} 枚举里抓，这样只需要读源码、不需要 MC 类，
     * 同时也能在有人新增样式却忘了补 lang 时立刻失败。
     */
    private static void requireBlockKeysCoverAllStyles(String code, Map<String, String> entries, Path styleSource)
            throws IOException {
        List<String> blockIds = readBlockIds(styleSource);
        if (blockIds.isEmpty()) {
            FAILURES.add("没能从 " + styleSource.getFileName() + " 解析出任何方块 id（枚举写法变了，本回归需同步）");
            return;
        }
        for (String blockId : blockIds) {
            String key = BLOCK_PREFIX + blockId;
            if (!entries.containsKey(key)) {
                FAILURES.add(code + ": 缺少键 " + key);
            }
        }
    }

    /** 抓 {@code SkyStyle} 枚举里第一段字符串常量，即 {@code blockId()} 的返回值。 */
    static List<String> readBlockIds(Path styleSource) throws IOException {
        if (!Files.isRegularFile(styleSource)) {
            FAILURES.add("找不到 " + styleSource);
            return List.of();
        }
        String source = stripComments(Files.readString(styleSource, StandardCharsets.UTF_8));
        Matcher body = Pattern.compile("enum\\s+SkyStyle\\s*\\{(.*?);", Pattern.DOTALL).matcher(source);
        if (!body.find()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Matcher entry = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*\\(\\s*\"([^\"]+)\"", Pattern.MULTILINE)
                .matcher(body.group(1));
        while (entry.find()) {
            ids.add(entry.group(2));
        }
        return ids;
    }

    /**
     * 不允许用重复的 {@code item.*} 键去掩盖注册根因。
     *
     * <p>判断依据：只要 lang 里出现了与某个方块同名的 {@code item.skyboxblock.<path>}，
     * 说明有人试图用资源绕过 {@code useBlockDescriptionPrefix()}；本模组与 1.21.1 都只用 {@code block.*}。
     */
    private static void requireNoDuplicateItemKeys(String code, Map<String, String> entries) {
        for (String key : entries.keySet()) {
            if (!key.startsWith("item." + MOD_ID + ".")) {
                continue;
            }
            String path = key.substring(("item." + MOD_ID + ".").length());
            if (entries.containsKey(BLOCK_PREFIX + path)) {
                FAILURES.add(code + ": " + key + " 是 " + BLOCK_PREFIX + path
                        + " 的重复镜像键（应当修注册处的 descriptionId 前缀，而不是补资源）");
            }
        }
    }

    private static void requireNonEmptyValues(String code, Map<String, String> entries) {
        entries.forEach((key, value) -> {
            if (value == null || value.trim().isEmpty()) {
                FAILURES.add(code + ": 键 " + key + " 的值是空的");
            }
        });
    }

    /** 与 1.21.1 参照工程逐键比对：键集一致、每个键的值一致。 */
    private static void requireParityWithReference(String code, Map<String, String> entries, Path referenceFile)
            throws IOException {
        if (!Files.isRegularFile(referenceFile)) {
            FAILURES.add("参照 lang 不存在：" + referenceFile);
            return;
        }
        Map<String, String> expected = readFlatJson(referenceFile);
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entries.containsKey(entry.getKey())) {
                FAILURES.add(code + ": 与 1.21.1 相比缺少键 " + entry.getKey());
            } else if (!entry.getValue().equals(entries.get(entry.getKey()))) {
                FAILURES.add(code + ": 键 " + entry.getKey() + " 名称不一致：26.2=\"" + entries.get(entry.getKey())
                        + "\" / 1.21.1=\"" + entry.getValue() + "\"");
            }
        }
        for (String key : entries.keySet()) {
            if (!expected.containsKey(key)) {
                FAILURES.add(code + ": 多出 1.21.1 里没有的键 " + key);
            }
        }
    }

    /** en_us 与 zh_cn 的键集必须完全一致，否则回退到英文时会漏字。 */
    private static void requireLangFilesAgree(Path langDir) throws IOException {
        Set<String> en = readFlatJson(langDir.resolve("en_us.json")).keySet();
        Set<String> zh = readFlatJson(langDir.resolve("zh_cn.json")).keySet();
        for (String key : en) {
            if (!zh.contains(key)) {
                FAILURES.add("zh_cn 缺少 en_us 里有的键 " + key);
            }
        }
        for (String key : zh) {
            if (!en.contains(key)) {
                FAILURES.add("en_us 缺少 zh_cn 里有的键 " + key);
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 极简扁平 JSON 读取：本模组的 lang 文件是单层 {@code "key": "value"} 对象。
     *
     * <p>刻意不引入 Gson/Jackson：这是 src/test 下的纯文本回归，
     * 需要能在没有 MC classpath 的情况下单独编译运行。
     */
    static Map<String, String> readFlatJson(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            FAILURES.add("找不到语言文件 " + file);
            return Map.of();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = STRING_PAIR.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            if (!seen.add(key)) {
                FAILURES.add(file.getFileName() + ": 键 " + key + " 重复定义");
                continue;
            }
            out.put(key, unescape(value));
        }
        String stripped = STRING_PAIR.matcher(text).replaceAll("");
        if (stripped.replaceAll("[{}\\s,]", "").length() > 0) {
            FAILURES.add(file.getFileName() + ": 不是单层扁平对象，存在本回归无法解析的内容");
        }
        if (out.isEmpty()) {
            FAILURES.add(file.getFileName() + ": 解析出 0 条键");
        }
        return out;
    }

    private static final Pattern STRING_PAIR =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripComments(String body) {
        StringBuilder sb = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '/') {
                while (i < body.length() && body.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < body.length() && !(body.charAt(i) == '*' && body.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (c == '"') {
                sb.append(c);
                i++;
                while (i < body.length()) {
                    char inside = body.charAt(i);
                    sb.append(inside);
                    i++;
                    if (inside == '\\' && i < body.length()) {
                        sb.append(body.charAt(i));
                        i++;
                    } else if (inside == '"') {
                        break;
                    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int matchingBrace(String source, int openBrace) {
        int depth = 0;
        int i = openBrace;
        boolean inString = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private LocalizationRegression() {
    }
}
