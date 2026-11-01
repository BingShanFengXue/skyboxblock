package dev.yg.skyboxblock.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 自定义天空盒配置的解析器：纯 JDK 实现，不引用任何 net.minecraft 类，便于服务端与客户端共用。
 *
 * <p>语法是 TOML 的一个极小子集：</p>
 * <pre>
 * # 注释到行尾（引号内的 # 不算注释）
 * enable = false
 *
 * test_sky_block_1 = {
 *     id = "skyboxblock:test_sky_block_1"
 *     picture = "全景图.png"
 *     creative_inventory = true
 *     lang_zh_cn = { name = "测试天空盒1" }
 *     lang_en_us = { name = "Test Sky Block" }
 * }
 * </pre>
 *
 * <p>解析分两个阶段，两阶段的失败边界不同（这一点决定了调用方能不能"救回半个文件"）：</p>
 * <ol>
 *   <li><b>语法阶段</b>（Lexer + 字段树）：花括号不平衡、引号未闭合、缺少 {@code =}、
 *       值位置非法、嵌套过深 —— 任意一条都让<b>整份文件作废</b>，
 *       返回空结果并只带一条错误（哪怕此前已经读到合法块）。</li>
 *   <li><b>语义阶段</b>（buildStyle）：缺 id / 缺 picture / 值非法 / 语言块非法 / 键名非法，
 *       都只跳过<b>那个块</b>，其余块照常返回。</li>
 * </ol>
 *
 * <p>另外：未知键只记 WARN 后忽略（不算失败）；重复 id 保留第一个，后者记 WARN 跳过；
 * 文本开头的 BOM（\uFEFF）由本解析器剥掉；所有文案统一成「文件名:行号: 中文说明」。</p>
 */
public final class SkyConfigParser {

    /** 键名（裸词）：只允许 ASCII 字母、数字、下划线、连字符。 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+");

    /** 语言代号：2~3 位小写字母，可再跟一段 2~4 位小写字母（zh_cn / pt_br / lzh）。 */
    private static final Pattern LANG_CODE = Pattern.compile("[a-z]{2,3}(_[a-z]{2,4})?");

    /** 样式 id：命名空间:路径。 */
    private static final Pattern STYLE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** 语言键前缀，例如 lang_zh_cn。 */
    private static final String LANG_PREFIX = "lang_";

    /** 对象最大嵌套层数，防止畸形文件把栈打穿。 */
    private static final int MAX_DEPTH = 32;

    /** id 为空等极端情况下的最后兜底显示名。 */
    private static final String FALLBACK_NAME = "未命名天空盒";

    private SkyConfigParser() {
    }

    /** 一个可用的天空盒样式。names 的键是语言代号（如 zh_cn），值是该语言的显示名。 */
    public record StyleSpec(String id, String picture, boolean creativeInventory, Map<String, String> names) {
        public StyleSpec {
            names = names == null ? Map.of() : Map.copyOf(names);
        }
    }

    /** 解析结果：errors 为空时 usable() 为真；enabled 是 enable 归一化后的布尔值。 */
    public record Result(boolean enabled, List<StyleSpec> styles,
                         List<String> warnings, List<String> errors) {
        public Result {
            styles = styles == null ? List.of() : List.copyOf(styles);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** 没有错误才算可用；语法错误会让整份文件不可用。 */
        public boolean usable() {
            return errors.isEmpty();
        }
    }

    /**
     * 解析配置全文。
     *
     * @param fileName 仅用于报错文案
     * @param text     文件全文（调用方按 UTF-8 读；开头的 BOM 由本方法剥掉）
     */
    public static Result parse(String fileName, String text) {
        String file = (fileName == null || fileName.isBlank()) ? "<内存配置>" : fileName;
        String source = text == null ? "" : text;
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Map<String, Node> top;
        try {
            top = new Lexer(file, source).readDocument();
        } catch (SyntaxError bad) {
            // 语法阶段一错到底：整份文件放弃，只带一条错误（此前攒下的警告保留，方便排查）
            return new Result(false, List.of(), warnings, List.of(bad.getMessage()));
        }

        boolean enabled = false;
        List<StyleSpec> styles = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Map.Entry<String, Node> entry : top.entrySet()) {
            String key = entry.getKey();
            Node node = entry.getValue();
            if (!KEY.matcher(key).matches()) {
                errors.add(msg(file, node.line(), "顶层键名 '" + key + "' 非法（只允许 A-Za-z0-9_-），已忽略"));
                continue;
            }
            if ("enable".equals(key)) {
                if (node.isObject() || !node.isBool()) {
                    errors.add(msg(file, node.line(), "enable 必须是布尔值（true/false/yes/no），已按 false 处理"));
                } else {
                    enabled = Boolean.parseBoolean(node.text());
                }
                continue;
            }
            if (!node.isObject()) {
                warnings.add(msg(file, node.line(), "未知键 '" + key + "'（既不是 enable 也不是块），已忽略"));
                continue;
            }
            StyleSpec spec = buildStyle(file, key, node, seenIds, warnings, errors);
            if (spec != null) {
                styles.add(spec);
            }
        }
        return new Result(enabled, styles, warnings, errors);
    }

    /**
     * 语言名回退链：精确码 → 语言段 → en_us → en → id 路径部分。
     * 空串名字不参与回退（否则会出现"有键但没名字"的黑洞）。
     */
    public static String displayName(StyleSpec spec, String languageCode) {
        if (spec == null) {
            return FALLBACK_NAME;
        }
        Map<String, String> names = spec.names() == null ? Map.of() : spec.names();
        String code = languageCode == null
                ? ""
                : languageCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!code.isEmpty()) {
            String exact = filled(names.get(code));
            if (exact != null) {
                return exact;
            }
            int cut = code.indexOf('_');
            if (cut > 0) {
                String segment = filled(names.get(code.substring(0, cut)));
                if (segment != null) {
                    return segment;
                }
            }
        }
        String universal = filled(names.get("en_us"));
        if (universal != null) {
            return universal;
        }
        String english = filled(names.get("en"));
        if (english != null) {
            return english;
        }
        String id = spec.id() == null ? "" : spec.id();
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isEmpty() ? FALLBACK_NAME : path;
    }

    // ── 语义阶段 ────────────────────────────────────────────────────────────

    /**
     * 校验并构造一个样式块。返回 null 表示该块被跳过，原因已经写进 errors 或 warnings。
     */
    private static StyleSpec buildStyle(String file, String blockName, Node block, Set<String> seenIds,
                                        List<String> warnings, List<String> errors) {
        List<String> blockErrors = new ArrayList<>();
        String id = null;
        String picture = null;
        boolean idSeen = false;
        boolean pictureSeen = false;
        boolean creative = false;
        Map<String, String> names = new LinkedHashMap<>();

        for (Map.Entry<String, Node> entry : block.fields().entrySet()) {
            String key = entry.getKey();
            Node value = entry.getValue();
            if (!KEY.matcher(key).matches()) {
                blockErrors.add(msg(file, value.line(),
                        "块 '" + blockName + "' 里的键名 '" + key + "' 非法（只允许 A-Za-z0-9_-）"));
                continue;
            }
            if ("id".equals(key)) {
                idSeen = true;
                String raw = scalar(file, "id", value, blockErrors);
                if (raw == null) {
                    continue;
                }
                if (!STYLE_ID.matcher(raw).matches()) {
                    blockErrors.add(msg(file, value.line(), "id '" + raw
                            + "' 非法（应形如 命名空间:路径，只允许小写字母/数字/下划线/点/连字符）"));
                } else {
                    id = raw;
                }
            } else if ("picture".equals(key)) {
                pictureSeen = true;
                String raw = scalar(file, "picture", value, blockErrors);
                if (raw == null) {
                    continue;
                }
                String problem = pictureProblem(raw);
                if (problem != null) {
                    blockErrors.add(msg(file, value.line(), problem));
                } else {
                    picture = raw;
                }
            } else if ("creative_inventory".equals(key)) {
                if (value.isObject() || !value.isBool()) {
                    blockErrors.add(msg(file, value.line(),
                            "creative_inventory 必须是布尔值（true/false/yes/no）"));
                } else {
                    creative = Boolean.parseBoolean(value.text());
                }
            } else if (key.startsWith(LANG_PREFIX)) {
                String code = key.substring(LANG_PREFIX.length());
                if (!LANG_CODE.matcher(code).matches()) {
                    warnings.add(msg(file, value.line(),
                            "未知键 '" + key + "'（语言代号 '" + code + "' 不合法），已忽略"));
                } else {
                    readLangBlock(file, key, code, value, names, blockErrors);
                }
            } else {
                warnings.add(msg(file, value.line(),
                        "块 '" + blockName + "' 里的未知键 '" + key + "'，已忽略"));
            }
        }

        if (!idSeen) {
            blockErrors.add(msg(file, block.line(), "块 '" + blockName + "' 缺少 id"));
        }
        if (!pictureSeen) {
            blockErrors.add(msg(file, block.line(), "块 '" + blockName + "' 缺少 picture"));
        }
        if (!blockErrors.isEmpty()) {
            errors.addAll(blockErrors);
            return null;
        }
        if (!seenIds.add(id)) {
            warnings.add(msg(file, block.line(),
                    "重复的 id '" + id + "'，已跳过后出现的块 '" + blockName + "'"));
            return null;
        }
        return new StyleSpec(id, picture, creative, Collections.unmodifiableMap(names));
    }

    /** 取标量字符串值；对象、布尔、空串都算非法值：问题写进 blockErrors 并返回 null。 */
    private static String scalar(String file, String field, Node node, List<String> blockErrors) {
        if (node.isObject()) {
            blockErrors.add(msg(file, node.line(), field + " 的值必须是字符串，不能是对象"));
            return null;
        }
        if (node.isBool()) {
            blockErrors.add(msg(file, node.line(), field + " 的值必须是字符串，不能是布尔值"));
            return null;
        }
        if (node.text().isEmpty()) {
            blockErrors.add(msg(file, node.line(), field + " 不能是空串"));
            return null;
        }
        return node.text();
    }

    /** 读取一个语言块；任何不合法都记成块级错误（只废掉当前块）。 */
    private static void readLangBlock(String file, String key, String code, Node value,
                                      Map<String, String> names, List<String> blockErrors) {
        if (!value.isObject()) {
            blockErrors.add(msg(file, value.line(),
                    "语言块 '" + key + "' 的值必须是对象，例如 { name = \"名字\" }"));
            return;
        }
        String name = null;
        for (Map.Entry<String, Node> entry : value.fields().entrySet()) {
            String field = entry.getKey();
            Node node = entry.getValue();
            if (!"name".equals(field)) {
                blockErrors.add(msg(file, node.line(),
                        "语言块 '" + key + "' 只允许 name 键，出现了 '" + field + "'"));
                return;
            }
            if (node.isObject()) {
                blockErrors.add(msg(file, node.line(), "语言块 '" + key + "' 的 name 必须是字符串"));
                return;
            }
            if (node.isBool()) {
                blockErrors.add(msg(file, node.line(),
                        "语言块 '" + key + "' 的 name 必须是字符串，不能是布尔值"));
                return;
            }
            if (node.text().isEmpty()) {
                blockErrors.add(msg(file, node.line(), "语言块 '" + key + "' 的 name 不能是空串"));
                return;
            }
            name = node.text();
        }
        if (name == null) {
            blockErrors.add(msg(file, value.line(), "语言块 '" + key + "' 缺少 name"));
            return;
        }
        names.put(code, name);
    }

    /** picture 合法性检查；返回 null 表示合法，否则返回中文说明。 */
    private static String pictureProblem(String picture) {
        if (picture.isEmpty()) {
            return "picture 不能是空串";
        }
        if (picture.indexOf('/') >= 0 || picture.indexOf('\\') >= 0) {
            return "picture 不能包含 '/' 或 '\\'";
        }
        if (".".equals(picture) || "..".equals(picture)) {
            return "picture 不能是 '.' 或 '..'";
        }
        if (!picture.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return "picture 必须以 .png 结尾（大小写不敏感）";
        }
        return null;
    }

    /** 过滤 null 与空串，避免空名字顶掉后面的回退层级。 */
    private static String filled(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 统一文案格式：文件名:行号: 中文说明。 */
    private static String msg(String file, int line, String text) {
        return file + ":" + line + ": " + text;
    }

    // ── 语法树节点 ──────────────────────────────────────────────────────────

    /** 语法树节点：要么是标量（字符串或布尔），要么是对象。 */
    private static final class Node {
        private final Map<String, Node> fields;
        private final String text;
        private final boolean bool;
        private final int line;

        private Node(Map<String, Node> fields, String text, boolean bool, int line) {
            this.fields = fields;
            this.text = text;
            this.bool = bool;
            this.line = line;
        }

        static Node scalar(String text, boolean bool, int line) {
            return new Node(null, text, bool, line);
        }

        static Node object(Map<String, Node> fields, int line) {
            return new Node(fields, "", false, line);
        }

        boolean isObject() {
            return fields != null;
        }

        boolean isBool() {
            return bool;
        }

        String text() {
            return text;
        }

        Map<String, Node> fields() {
            return fields == null ? Map.of() : fields;
        }

        int line() {
            return line;
        }
    }

    // ── 词法与结构解析 ──────────────────────────────────────────────────────

    /** 词法单元种类。 */
    private enum TokenKind { WORD, STRING, EQ, LBRACE, RBRACE, EOF }

    /** 词法单元；line 是起始行号（字符串取开引号所在行）。 */
    private record Token(TokenKind kind, String text, int line) { }

    /** 语法阶段的致命错误：抛出即整份文件作废。 */
    private static final class SyntaxError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntaxError(String message) {
            super(message, null, false, false);
        }
    }

    /** 词法器 + 结构解析：只产出 token 流水与字段树，语义判断全部留给上层。 */
    private static final class Lexer {
        private final String file;
        private final String source;
        private int pos;
        private int line = 1;

        Lexer(String file, String source) {
            this.file = file;
            this.source = source;
        }

        /** 顶层字段表；读到 EOF 结束。 */
        Map<String, Node> readDocument() {
            return readFields(true, 0);
        }

        /** 收集 key = value / key = { ... } 语句；topLevel 时以 EOF 结束，否则以 '}' 结束。 */
        private Map<String, Node> readFields(boolean topLevel, int depth) {
            Map<String, Node> fields = new LinkedHashMap<>();
            while (true) {
                Token token = next();
                if (token.kind() == TokenKind.EOF) {
                    if (topLevel) {
                        return fields;
                    }
                    throw fail(token.line(), "花括号不平衡：对象没有收尾的 '}'");
                }
                if (token.kind() == TokenKind.RBRACE) {
                    if (!topLevel) {
                        return fields;
                    }
                    throw fail(token.line(), "花括号不平衡：顶层出现了多余的 '}'");
                }
                if (token.kind() != TokenKind.WORD) {
                    throw fail(token.line(), "语句必须以键名（裸词）开始，缺少 '=' 或键名");
                }
                String key = token.text();
                Token assign = next();
                if (assign.kind() != TokenKind.EQ) {
                    throw fail(assign.line(), "键 '" + key + "' 后缺少 '='");
                }
                fields.put(key, readValue(depth));
            }
        }

        private Node readValue(int depth) {
            Token token = next();
            switch (token.kind()) {
                case STRING:
                    return Node.scalar(token.text(), false, token.line());
                case WORD:
                    return booleanWord(token);
                case LBRACE:
                    if (depth >= MAX_DEPTH) {
                        throw fail(token.line(), "嵌套层级过深（上限 " + MAX_DEPTH + " 层）");
                    }
                    return Node.object(readFields(false, depth + 1), token.line());
                case EQ:
                    throw fail(token.line(), "'=' 后面缺少值");
                case RBRACE:
                    throw fail(token.line(), "缺少值，或花括号不平衡");
                default:
                    throw fail(token.line(), "语句缺少值（文件意外结束）");
            }
        }

        /** true/false/yes/no（大小写不敏感）归一化成布尔，其余裸词原样保留。 */
        private Node booleanWord(Token token) {
            String lower = token.text().toLowerCase(Locale.ROOT);
            if ("true".equals(lower) || "yes".equals(lower)) {
                return Node.scalar("true", true, token.line());
            }
            if ("false".equals(lower) || "no".equals(lower)) {
                return Node.scalar("false", true, token.line());
            }
            return Node.scalar(token.text(), false, token.line());
        }

        private Token next() {
            skipBlank();
            if (pos >= source.length()) {
                return new Token(TokenKind.EOF, "", line);
            }
            char c = source.charAt(pos);
            if (c == '=') {
                pos++;
                return new Token(TokenKind.EQ, "=", line);
            }
            if (c == '{') {
                pos++;
                return new Token(TokenKind.LBRACE, "{", line);
            }
            if (c == '}') {
                pos++;
                return new Token(TokenKind.RBRACE, "}", line);
            }
            if (c == '"') {
                return readString();
            }
            return readWord();
        }

        /** 跳过空白与注释：'#' 到行尾；引号内的 '#' 由 readString 整段吃掉，不会走到这里。 */
        private void skipBlank() {
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                } else if (c == '#') {
                    while (pos < source.length() && source.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (Character.isWhitespace(c)) {
                    pos++;
                } else {
                    return;
                }
            }
        }

        /** 双引号字符串：只识别 \" 与 \\，其余反斜杠原样保留（Windows 路径不丢字符）。 */
        private Token readString() {
            int startLine = line;
            pos++; // 开引号
            StringBuilder text = new StringBuilder();
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == '\\') {
                    if (pos + 1 >= source.length()) {
                        throw fail(startLine, "引号未闭合：字符串在文件末尾仍没有收尾");
                    }
                    char follow = source.charAt(pos + 1);
                    if (follow == '"' || follow == '\\') {
                        text.append(follow);
                    } else {
                        text.append('\\').append(follow);
                    }
                    if (follow == '\n') {
                        line++;
                    }
                    pos += 2;
                } else if (c == '"') {
                    pos++;
                    return new Token(TokenKind.STRING, text.toString(), startLine);
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    text.append(c);
                    pos++;
                }
            }
            throw fail(startLine, "引号未闭合：缺少收尾的 '\"'");
        }

        /** 裸词：不含空白、引号、花括号、'#'、'='；可含反斜杠与冒号，合法性交给语义阶段。 */
        private Token readWord() {
            int startLine = line;
            int start = pos;
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (Character.isWhitespace(c) || c == '"' || c == '{' || c == '}' || c == '#' || c == '=') {
                    break;
                }
                pos++;
            }
            return new Token(TokenKind.WORD, source.substring(start, pos), startLine);
        }

        private SyntaxError fail(int atLine, String text) {
            return new SyntaxError(msg(file, atLine, text));
        }
    }
}
