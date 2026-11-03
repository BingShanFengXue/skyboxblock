package dev.yg.skyboxblock.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 自定义天空盒配置解析器的回归：纯 JDK，不依赖 Minecraft，可直接 java 运行。
 *
 * <p>覆盖正常路径（单块/多块/同行对象/嵌套语言块/中文/反斜杠/引号内 '#'/BOM/enable 各种写法）、
 * 每个错误分支（id/picture/空串/花括号/引号/'='/语言块）、未知键与重复 id 的降级行为，
 * 以及 displayName 的整条回退链。</p>
 */
public final class SkyConfigRegression {

    /** 与生产调用方一致的文件名，用来验证「文件名:行号: 中文说明」文案格式。 */
    private static final String FILE = "skybox.cfg";

    /** 文案格式：文件名:行号: 说明。 */
    private static final Pattern MESSAGE = Pattern.compile("skybox\\.cfg:\\d+: .+");

    private static int checks;

    private SkyConfigRegression() {
    }

    // ── 断言设施 ────────────────────────────────────────────────────────────

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            System.out.println("FAIL: " + name);
            throw new AssertionError(name);
        }
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(ok, ok ? name : name + " → 期望[" + expected + "] 实际[" + actual + "]");
    }

    private static void errorHas(String name, SkyConfigParser.Result result, String needle) {
        check(contains(result.errors(), needle),
                name + " → 期望错误含[" + needle + "]，实际[" + join(result.errors()) + "]");
    }

    private static void warnHas(String name, SkyConfigParser.Result result, String needle) {
        check(contains(result.warnings(), needle),
                name + " → 期望警告含[" + needle + "]，实际[" + join(result.warnings()) + "]");
    }

    /** 每条错误/警告都必须满足「文件名:行号: 中文说明」。 */
    private static void formatOk(String name, SkyConfigParser.Result result) {
        boolean ok = true;
        for (String message : result.warnings()) {
            ok &= MESSAGE.matcher(message).matches();
        }
        for (String message : result.errors()) {
            ok &= MESSAGE.matcher(message).matches();
        }
        check(ok, name + " → 文案必须是 文件名:行号: 中文说明，实际[" + join(result.warnings())
                + "][" + join(result.errors()) + "]");
    }

    /** 断言恰好一个块并取出它，避免测试自身抛 IndexOutOfBounds 掩盖真实失败。 */
    private static SkyConfigParser.StyleSpec one(SkyConfigParser.Result result, String name) {
        check(result.styles().size() == 1,
                name + " → 期望恰好 1 块，实际 " + result.styles().size() + "，错误[" + join(result.errors()) + "]");
        return result.styles().get(0);
    }

    private static boolean contains(List<String> list, String needle) {
        for (String item : list) {
            if (item.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> list) {
        return String.join(" | ", list);
    }

    private static String lines(String... parts) {
        return String.join("\n", parts);
    }

    private static SkyConfigParser.Result parse(String text) {
        return SkyConfigParser.parse(FILE, text);
    }

    /** 一个坏块：必须不可用、必须跳过该块，且错误文案里出现指定片段。 */
    private static void badBlock(String name, String body, String needle) {
        SkyConfigParser.Result result = parse("sky = {\n" + body + "\n}");
        check(!result.usable(), name + " → 必须报错");
        check(result.styles().isEmpty(), name + " → 必须跳过该块");
        errorHas(name, result, needle);
    }

    // ── 用例正文 ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        normalSample();
        enableFlags();
        bom();
        commentsQuotesBackslash();
        nonAscii();
        bareWords();
        unknownKeys();
        topLevelObject();
        duplicateId();
        badValues();
        mixedBlocks();
        languageBlocks();
        structuralErrors();
        deepNesting();
        illegalKeys();
        badEnable();
        displayNames();
        edgeInputs();

        String report = "PASS: " + checks + " assertions\n";
        System.out.print(report);
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            Files.writeString(path, report, StandardCharsets.UTF_8);
        }
    }

    /** 官方样例：单块、嵌套语言块、同行对象。 */
    private static void normalSample() {
        String sample = lines(
                "# 注释：自定义天空盒",
                "enable = false",
                "",
                "test_sky_block_1 = {",
                "    id = \"skyboxblock:test_sky_block_1\"",
                "    picture = \"全景图.png\"",
                "    creative_inventory = true",
                "    lang_zh_cn = { name = \"测试天空盒1\" }",
                "    lang_en_us = { name = \"Test Sky Block\" }",
                "}");
        SkyConfigParser.Result result = parse(sample);
        check(result.usable(), "样例必须可用");
        check(!result.enabled(), "样例 enable=false");
        eq(1, result.styles().size(), "样例只有一个块");
        SkyConfigParser.StyleSpec spec = one(result, "样例块");
        eq("skyboxblock:test_sky_block_1", spec.id(), "样例 id");
        eq("全景图.png", spec.picture(), "样例 picture 中文文件名");
        check(spec.creativeInventory(), "样例 creative_inventory=true");
        eq(2, spec.names().size(), "样例两个语言块");
        eq("测试天空盒1", spec.names().get("zh_cn"), "lang_zh_cn 名字");
        eq("Test Sky Block", spec.names().get("en_us"), "lang_en_us 名字");
        check(result.warnings().isEmpty(), "样例不应有警告");
        check(result.errors().isEmpty(), "样例不应有错误");
        formatOk("样例文案格式", result);

        // 多块：顺序保持，同行对象也成立
        SkyConfigParser.Result multi = parse(lines(
                "enable = true",
                "sky_a = { id = \"skyboxblock:a\" picture = \"a.png\" }",
                "sky_b = {",
                "    id = \"skyboxblock:b\"",
                "    picture = \"b.PNG\"",
                "}",
                "sky_c = { id = \"skyboxblock:c\" picture = \"c.png\" lang_en_us = { name = \"C\" } }"));
        check(multi.usable(), "多块配置可用");
        check(multi.enabled(), "多块 enable=true");
        eq(3, multi.styles().size(), "三块都被保留");
        eq("skyboxblock:a", multi.styles().get(0).id(), "第一块保持声明顺序");
        eq("skyboxblock:b", multi.styles().get(1).id(), "第二块保持声明顺序");
        eq("skyboxblock:c", multi.styles().get(2).id(), "第三块保持声明顺序");
        eq("b.PNG", multi.styles().get(1).picture(), "png 后缀大小写不敏感");
        eq("C", SkyConfigParser.displayName(multi.styles().get(2), "en_us"), "同行语言块生效");
        check(multi.styles().get(2).creativeInventory() == false, "creative_inventory 缺省 false");
    }

    /** enable 的各种写法。 */
    private static void enableFlags() {
        check(!parse("").enabled(), "空文件 enable 缺省 false");
        check(!parse("# 只有注释\n").enabled(), "只有注释时 enable 缺省 false");
        check(parse("enable = yes\n").enabled(), "enable=yes 归一化为 true");
        check(parse("enable = TRUE\n").enabled(), "enable=TRUE 大小写不敏感");
        check(!parse("enable = no\n").enabled(), "enable=no 归一化为 false");
        check(parse("enable = Yes").enabled(), "enable=Yes 大小写不敏感");
        check(parse("  enable   =   true   # 行尾注释\n").enabled(), "行首空白与行尾注释被忽略");
        check(parse("\n\n\nenable = true\n\n").enabled(), "空行被忽略");
    }

    /** BOM 开头。 */
    private static void bom() {
        SkyConfigParser.Result result = parse("\uFEFFenable = true\n"
                + "sky = { id = \"skyboxblock:bom\" picture = \"bom.png\" }\n");
        check(result.enabled(), "BOM 开头仍读到 enable=true");
        check(result.usable(), "BOM 不产生错误");
        eq(1, result.styles().size(), "BOM 开头仍能解析块");
        eq("skyboxblock:bom", one(result, "BOM 块").id(), "BOM 后的 id 正确");
    }

    /** 注释、引号内 '#'、Windows 反斜杠、两种转义。 */
    private static void commentsQuotesBackslash() {
        SkyConfigParser.Result hash = parse(lines(
                "enable = true",
                "sky = {",
                "    id = \"skyboxblock:q\"",
                "    picture = \"q.png\"",
                "    lang_en_us = { name = \"a # b\" }   # 行尾注释",
                "}"));
        check(hash.usable(), "引号内 '#' 不产生错误");
        eq(1, hash.styles().size(), "引号内 '#' 不截断：块仍完整");
        eq("a # b", SkyConfigParser.displayName(one(hash, "引号内 # 块"), "en_us"), "引号内 '#' 原样保留");

        SkyConfigParser.Result win = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:win\"",
                "    picture = \"win.png\"",
                "    lang_en_us = { name = \"C:\\temp\\sky\" }",
                "}"));
        eq("C:\\temp\\sky",
                SkyConfigParser.displayName(one(win, "反斜杠块"), "en_us"), "Windows 反斜杠原样保留");

        SkyConfigParser.Result esc = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:esc\"",
                "    picture = \"esc.png\"",
                "    lang_en_us = { name = \"a\\\"b\\\\c\" }",
                "}"));
        eq("a\"b\\c", SkyConfigParser.displayName(one(esc, "转义块"), "en_us"), "只识别 \\\" 与 \\\\ 两种转义");
        check(parse("# a = { b = 'c'\n").usable(), "整行注释不参与解析");
    }

    /** 中文与非 ASCII。 */
    private static void nonAscii() {
        SkyConfigParser.Result result = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:cjk\"",
                "    picture = \"天空盒🌇.png\"",
                "    lang_zh_cn = { name = \"天空盒·壹\" }",
                "    lang_ja_jp = { name = \"空の箱 🎐\" }",
                "}"));
        check(result.usable(), "非 ASCII 内容不产生错误");
        SkyConfigParser.StyleSpec spec = one(result, "非 ASCII 块");
        eq("天空盒🌇.png", spec.picture(), "非 ASCII 文件名保留");
        eq("空の箱 🎐", SkyConfigParser.displayName(spec, "ja_jp"), "非 ASCII 名字保留");
        eq("天空盒·壹", SkyConfigParser.displayName(spec, "zh_cn"), "中文名字保留");
    }

    /** 裸词值与布尔归一化。 */
    private static void bareWords() {
        SkyConfigParser.Result result = parse(lines(
                "sky = {",
                "    id = skyboxblock:bare",
                "    picture = 全景图.png",
                "    creative_inventory = YES",
                "}"));
        check(result.usable(), "裸词值可用");
        SkyConfigParser.StyleSpec spec = one(result, "裸词块");
        eq("skyboxblock:bare", spec.id(), "id 支持裸词");
        eq("全景图.png", spec.picture(), "picture 支持裸词");
        check(spec.creativeInventory(), "creative_inventory=YES 归一化为 true");
    }

    /** 未知键只警告。 */
    private static void unknownKeys() {
        SkyConfigParser.Result result = parse(lines(
                "theme = \"dark\"",
                "sky = {",
                "    id = \"skyboxblock:u\"",
                "    picture = \"u.png\"",
                "    speed = \"fast\"",
                "}"));
        check(result.usable(), "未知键不导致失败");
        eq(1, result.styles().size(), "未知键不影响块数量");
        eq(2, result.warnings().size(), "顶层与块内未知键各一条警告");
        warnHas("顶层未知键警告", result, "未知键 'theme'");
        warnHas("块内未知键警告", result, "未知键 'speed'");
        formatOk("未知键文案格式", result);
    }

    /** 顶层裸对象按块校验：缺 id/缺 picture 报错，但只废掉这一块。 */
    private static void topLevelObject() {
        SkyConfigParser.Result result = parse(lines(
                "meta = { version = \"1\" }",
                "sky = { id = \"skyboxblock:m\" picture = \"m.png\" }"));
        check(!result.usable(), "顶层裸对象缺 id/picture 必须报错");
        eq(1, result.styles().size(), "合法块不受坏块影响");
        eq("skyboxblock:m", result.styles().get(0).id(), "合法块内容正确");
        errorHas("缺 id 报错", result, "缺少 id");
        errorHas("缺 picture 报错", result, "缺少 picture");
        warnHas("meta 内未知键 version", result, "'version'");
    }

    /** 重复 id 只保留第一个。 */
    private static void duplicateId() {
        SkyConfigParser.Result result = parse(lines(
                "a = { id = \"skyboxblock:dup\" picture = \"first.png\" }",
                "b = { id = \"skyboxblock:dup\" picture = \"second.png\" }"));
        check(result.usable(), "重复 id 只是警告不是错误");
        eq(1, result.styles().size(), "重复 id 只保留第一个块");
        eq("first.png", result.styles().get(0).picture(), "保留的是第一个 id 的块");
        warnHas("重复 id 警告", result, "重复的 id");
        formatOk("重复 id 文案格式", result);
    }

    /** 每个值相关的错误分支。 */
    private static void badValues() {
        badBlock("id 非法（大写）", "    id = \"Skyblock:Bad\"\n    picture = \"a.png\"", "'Skyblock:Bad' 非法");
        badBlock("id 缺命名空间", "    id = \"test\"\n    picture = \"a.png\"", "非法");
        badBlock("id 空串", "    id = \"\"\n    picture = \"a.png\"", "不能是空串");
        badBlock("缺 id", "    picture = \"a.png\"", "缺少 id");
        badBlock("缺 picture", "    id = \"skyboxblock:x\"", "缺少 picture");
        badBlock("picture 带斜杠", "    id = \"skyboxblock:x\"\n    picture = \"a/b.png\"", "不能包含");
        badBlock("picture 带反斜杠", "    id = \"skyboxblock:x\"\n    picture = a\\b.png", "不能包含");
        badBlock("picture 非 png", "    id = \"skyboxblock:x\"\n    picture = \"a.jpg\"", "必须以 .png 结尾");
        badBlock("picture 空串", "    id = \"skyboxblock:x\"\n    picture = \"\"", "不能是空串");
        badBlock("picture 是单点", "    id = \"skyboxblock:x\"\n    picture = \".\"", "不能是 '.' 或 '..'");
        badBlock("picture 是双点", "    id = \"skyboxblock:x\"\n    picture = \"..\"", "不能是 '.' 或 '..'");
        badBlock("creative_inventory 非布尔",
                "    id = \"skyboxblock:x\"\n    picture = \"a.png\"\n    creative_inventory = \"true\"",
                "creative_inventory 必须是布尔值");
        badBlock("id 值是对象",
                "    id = { a = \"b\" }\n    picture = \"a.png\"", "不能是对象");
        badBlock("picture 值是布尔",
                "    id = \"skyboxblock:x\"\n    picture = true", "不能是布尔值");
    }

    /** 单块错误只跳过自己。 */
    private static void mixedBlocks() {
        SkyConfigParser.Result result = parse(lines(
                "bad = { id = \"skyboxblock:bad\" picture = \"bad.jpg\" }",
                "good = { id = \"skyboxblock:good\" picture = \"good.png\" }"));
        eq(1, result.styles().size(), "坏块只跳过自己");
        eq("skyboxblock:good", result.styles().get(0).id(), "好块照常返回");
        eq(1, result.errors().size(), "一个坏块只产生一条错误");
        formatOk("坏块文案格式", result);
    }

    /** 语言块的各种错误与合法代号。 */
    private static void languageBlocks() {
        SkyConfigParser.Result notObject = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:l1\"",
                "    picture = \"l1.png\"",
                "    lang_zh_cn = \"测试\"",
                "}"));
        check(!notObject.usable(), "lang 值不是对象 → 报错");
        check(notObject.styles().isEmpty(), "lang 值不是对象 → 跳过该块");
        errorHas("lang 值不是对象", notObject, "必须是对象");

        SkyConfigParser.Result extraField = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:l2\"",
                "    picture = \"l2.png\"",
                "    lang_zh_cn = { name = \"甲\" desc = \"乙\" }",
                "}"));
        check(!extraField.usable(), "lang 块含未知键 → 报错");
        errorHas("lang 块含未知键", extraField, "只允许 name");

        SkyConfigParser.Result emptyName = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:l3\"",
                "    picture = \"l3.png\"",
                "    lang_zh_cn = { name = \"\" }",
                "}"));
        errorHas("lang name 空串", emptyName, "不能是空串");

        SkyConfigParser.Result missingName = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:l4\"",
                "    picture = \"l4.png\"",
                "    lang_zh_cn = { }",
                "}"));
        errorHas("lang 块缺 name", missingName, "缺少 name");

        SkyConfigParser.Result badCode = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:l5\"",
                "    picture = \"l5.png\"",
                "    lang_ZH_cn = { name = \"甲\" }",
                "}"));
        check(badCode.usable(), "语言代号非法只是未知键警告");
        eq(0, one(badCode, "非法语言代号块").names().size(), "非法代号不参与回退");
        warnHas("非法语言代号警告", badCode, "语言代号");

        SkyConfigParser.Result codes = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:l6\"",
                "    picture = \"l6.png\"",
                "    lang_lzh = { name = \"文言\" }",
                "    lang_pt_br = { name = \"Céu\" }",
                "}"));
        eq(2, one(codes, "合法语言代号块").names().size(), "lzh 与 pt_br 都是合法代号");
        eq("Céu", SkyConfigParser.displayName(codes.styles().get(0), "pt_br"), "pt_br 精确命中");
        eq("文言", SkyConfigParser.displayName(codes.styles().get(0), "lzh"), "lzh 精确命中");
    }

    /** 结构错误：整份文件放弃。 */
    private static void structuralErrors() {
        SkyConfigParser.Result unclosed = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:s1\"",
                "    picture = \"s1.png\""));
        check(!unclosed.usable(), "缺 '}' → 不可用");
        eq(1, unclosed.errors().size(), "结构错误只带一条错误");
        eq(0, unclosed.styles().size(), "结构错误返回空结果");
        check(!unclosed.enabled(), "结构错误 enabled=false");
        errorHas("缺 '}' 文案", unclosed, "花括号不平衡");
        formatOk("缺 '}' 文案格式", unclosed);

        SkyConfigParser.Result extra = parse(lines(
                "sky = { id = \"skyboxblock:s2\" picture = \"s2.png\" }",
                "}"));
        eq(1, extra.errors().size(), "多余 '}' → 一条错误");
        eq(0, extra.styles().size(), "多余 '}' 也让整份放弃（哪怕前面有合法块）");
        errorHas("多余 '}' 文案", extra, "花括号不平衡");

        SkyConfigParser.Result quote = parse(lines(
                "enable = true",
                "sky = {",
                "    id = \"skyboxblock:s3\"",
                "    picture = \"s3.png",
                "}"));
        eq(1, quote.errors().size(), "引号未闭合 → 一条错误");
        check(quote.errors().get(0).startsWith("skybox.cfg:4: "), "引号未闭合定位到开引号所在行");
        errorHas("引号未闭合文案", quote, "引号未闭合");
        check(!quote.enabled(), "引号未闭合时 enable 也回到 false");

        SkyConfigParser.Result noEquals = parse(lines(
                "enable true",
                "sky = { id = \"skyboxblock:s4\" picture = \"s4.png\" }"));
        eq(1, noEquals.errors().size(), "缺 '=' → 一条错误");
        eq(0, noEquals.styles().size(), "缺 '=' 整份放弃");
        errorHas("缺 '=' 文案", noEquals, "缺少 '='");

        SkyConfigParser.Result loneKey = parse("enable");
        eq(1, loneKey.errors().size(), "键后直接 EOF → 一条错误");
        errorHas("键后 EOF 文案", loneKey, "缺少 '='");

        SkyConfigParser.Result braceKey = parse(lines(
                "= \"x\"",
                "sky = { id = \"skyboxblock:s5\" picture = \"s5.png\" }"));
        eq(1, braceKey.errors().size(), "键名位置非法 → 一条错误");
        eq(0, braceKey.styles().size(), "键名位置非法 → 整份放弃");
        errorHas("键名位置非法文案", braceKey, "必须以键名");
    }

    /** 嵌套过深按结构错误处理，不会抛栈溢出。 */
    private static void deepNesting() {
        StringBuilder deep = new StringBuilder("root = ");
        for (int i = 0; i < 40; i++) {
            deep.append("{ x = ");
        }
        deep.append("\"v\"");
        for (int i = 0; i < 40; i++) {
            deep.append(" }");
        }
        SkyConfigParser.Result result = parse(deep.toString());
        eq(1, result.errors().size(), "嵌套过深按结构错误处理");
        errorHas("嵌套过深文案", result, "嵌套层级过深");
    }

    /** 含中文的键名非法。 */
    private static void illegalKeys() {
        SkyConfigParser.Result top = parse(lines(
                "天空 = \"甲\"",
                "sky = { id = \"skyboxblock:k\" picture = \"k.png\" }"));
        check(!top.usable(), "顶层中文键名非法 → 记错误");
        eq(1, top.styles().size(), "非法键名只忽略该行，其他块保留");
        errorHas("顶层中文键名文案", top, "非法");

        SkyConfigParser.Result inside = parse(lines(
                "sky = {",
                "    id = \"skyboxblock:k2\"",
                "    picture = \"k2.png\"",
                "    名字 = \"甲\"",
                "}"));
        check(!inside.usable(), "块内中文键名非法 → 报错");
        eq(0, inside.styles().size(), "块内非法键名废掉该块");
        errorHas("块内中文键名文案", inside, "键名");
    }

    /** enable 不是布尔。 */
    private static void badEnable() {
        SkyConfigParser.Result result = parse(lines(
                "enable = \"true\"",
                "sky = { id = \"skyboxblock:e\" picture = \"e.png\" }"));
        check(!result.usable(), "enable 是非布尔 → 报错");
        check(!result.enabled(), "enable 非法时按 false 处理");
        eq(1, result.styles().size(), "enable 非法不影响块解析");
        errorHas("enable 非布尔文案", result, "enable 必须是布尔值");

        SkyConfigParser.Result object = parse("enable = { a = \"b\" }\n");
        check(!object.usable(), "enable 是对象 → 报错");
    }

    /** displayName 的整条回退链。 */
    private static void displayNames() {
        SkyConfigParser.StyleSpec spec = new SkyConfigParser.StyleSpec(
                "skyboxblock:test_sky_block_1", "p.png", false,
                Map.of("zh_cn", "简中", "zh", "中文", "en_us", "English US", "en", "English"));
        eq("简中", SkyConfigParser.displayName(spec, "zh_cn"), "回退层级1：精确码 zh_cn");
        eq("中文", SkyConfigParser.displayName(spec, "zh_tw"), "回退层级2：语言段 zh");
        eq("English US", SkyConfigParser.displayName(spec, "ja_jp"), "回退层级3：en_us");

        SkyConfigParser.StyleSpec enOnly = new SkyConfigParser.StyleSpec(
                "skyboxblock:x", "p.png", false, Map.of("en", "English"));
        eq("English", SkyConfigParser.displayName(enOnly, "ja_jp"), "回退层级4：en");

        SkyConfigParser.StyleSpec noNames = new SkyConfigParser.StyleSpec(
                "skyboxblock:test_sky_block_1", "p.png", false, Map.of());
        eq("test_sky_block_1", SkyConfigParser.displayName(noNames, "ja_jp"), "回退层级5：id 路径部分");

        eq("简中", SkyConfigParser.displayName(spec, "ZH-CN"), "语言码大写与短横线归一");
        eq("English US", SkyConfigParser.displayName(spec, null), "语言码为 null 时回退到 en_us");
        eq("English US", SkyConfigParser.displayName(spec, "   "), "语言码为空白时回退到 en_us");

        SkyConfigParser.StyleSpec blank = new SkyConfigParser.StyleSpec(
                "skyboxblock:z", "p.png", false, Map.of("zh_cn", "", "en_us", "US"));
        eq("US", SkyConfigParser.displayName(blank, "zh_cn"), "空串名字继续往下一层回退");

        check(!SkyConfigParser.displayName(spec, "zh_cn").isEmpty(), "回退结果永不为空串");
        eq("未命名天空盒", SkyConfigParser.displayName(
                new SkyConfigParser.StyleSpec("", "p.png", false, Map.of()), "ja_jp"), "id 为空时用兜底名");

        // 集成：解析出的块直接走回退链
        SkyConfigParser.Result result = parse(lines(
                "sky = { id = \"skyboxblock:int\" picture = \"i.png\" lang_en_us = { name = \"Integrated\" } }"));
        eq("Integrated", SkyConfigParser.displayName(one(result, "集成块"), "de_de"), "解析结果参与回退链");
    }

    /** 极端输入不崩。 */
    private static void edgeInputs() {
        SkyConfigParser.Result empty = parse("");
        check(empty.usable(), "空文件可用（无错误）");
        eq(0, empty.styles().size(), "空文件没有块");
        eq(0, empty.warnings().size(), "空文件没有警告");

        check(parse("enable = true").enabled(), "最后一行没有换行也能解析");
        check(SkyConfigParser.parse(null, null).usable(), "null 入参不抛异常");
        check(parse("\uFEFF").usable(), "只有 BOM 的文件可用");
        check(parse("sky = { id = \"skyboxblock:t\" picture = \"t.png\" }   ").usable(), "行尾空白不影响解析");
    }
}
