#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""静态校验 /skyboxblock 服务端指令的双语本地化（纯文本，无 Gradle、无 MC 运行时、无网络）。

检查范围（只读，不改动任何文件）::

    src/main/java/dev/yg/skyboxblock/server/SkyboxServerCommands.java
    mc26.2/src/main/java/dev/yg/skyboxblock/server/SkyboxServerCommands.java
    src/main/resources/assets/skyboxblock/lang/{en_us,zh_cn}.json
    mc26.2/src/main/resources/assets/skyboxblock/lang/{en_us,zh_cn}.json

七条断言：

1. Java 里每个 ``localized("<key>", "<fallback>", ...)`` / ``timeChange(source, "<key>", "<fallback>", ...)``
   的 key 都必须在两个语言文件里存在。
2. zh_cn 的值必须与 Java 里的 fallback 逐字符相同 —— 证明中文原文只是搬进资源，没有被改写。
3. 占位符个数三方一致：Java 动态实参个数 == zh_cn 个数 == en_us 个数
   （支持 ``%s`` 与 ``%1$s`` 两种写法，同一个字符串里不允许混用）。
4. 每个工程里 en_us 与 zh_cn 的键集完全相同（缺键会让 en_us 回退时漏字）。
5. 根工程与 mc26.2 的键集相同、同名键的值也相同（26.2 的 LocalizationRegression 就是这么比的）。
6. 语言文件里没有 ``command.skyboxblock.server.*`` 的孤儿键（Java 不引用的键 = 拼写错误）。
7. Java 源码里所有含中文的字符串字面量片段都正好是某个 localized/timeChange 的 fallback 片段
   （多行 ``+`` 拼接按片段计），且只有 success/failure 的 String 桥接还在用 ``Component.literal``，
   没有绕开本地化的反馈。

用法::

    python tools/verify-command-locales.py [项目根]

退出码 0 = 全通过，1 = 有失败项。
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path

SERVER_CLASS = "src/main/java/dev/yg/skyboxblock/server/SkyboxServerCommands.java"
LANG_DIR = "src/main/resources/assets/skyboxblock/lang"
VARIANTS = (("1.21.1", "."), ("26.2", "mc26.2"))
LANGS = ("en_us", "zh_cn")
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
PLACEHOLDER = re.compile(r"%(\d+\$)?s")
EXPECTED_LITERAL_BRIDGES = 2
INDENTED_CONCAT = re.compile(r"\s*\+\s*")

failures = []


def fail(message):
    failures.append(message)


# ────────────────────────────── Java 源码扫描 ──────────────────────────────


def strip_comments(source):
    """去掉 // 与 /* */ 注释，保留字符串字面量原样（注释里的引号不能干扰扫描）。"""
    out = []
    i = 0
    length = len(source)
    while i < length:
        char = source[i]
        if char == "/" and i + 1 < length and source[i + 1] == "/":
            while i < length and source[i] != "\n":
                i += 1
        elif char == "/" and i + 1 < length and source[i + 1] == "*":
            i += 2
            while i + 1 < length and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i = min(i + 2, length)
        elif char in "\"'":
            quote = char
            out.append(char)
            i += 1
            while i < length:
                out.append(source[i])
                if source[i] == "\\" and i + 1 < length:
                    out.append(source[i + 1])
                    i += 2
                    continue
                i += 1
                if source[i - 1] == quote:
                    break
        else:
            out.append(char)
            i += 1
    return "".join(out)


def unescape_java(literal):
    """把 Java 字符串字面量的内容还原成真实字符。"""
    out = []
    i = 0
    while i < len(literal):
        char = literal[i]
        if char != "\\" or i + 1 >= len(literal):
            out.append(char)
            i += 1
            continue
        nxt = literal[i + 1]
        i += 2
        if nxt == "n":
            out.append("\n")
        elif nxt == "t":
            out.append("\t")
        elif nxt == "r":
            out.append("\r")
        elif nxt == '"':
            out.append('"')
        elif nxt == "'":
            out.append("'")
        elif nxt == "\\":
            out.append("\\")
        elif nxt == "u" and i + 4 <= len(literal):
            out.append(chr(int(literal[i : i + 4], 16)))
            i += 4
        else:
            out.append(nxt)
    return "".join(out)


def split_arguments(text):
    """按顶层逗号切分实参；只保留非空的顶层片段。"""
    pieces = []
    depth = 0
    start = 0
    i = 0
    while i < len(text):
        char = text[i]
        if char in "\"'":
            quote = char
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                    continue
                i += 1
                if text[i - 1] == quote:
                    break
            continue
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            pieces.append(text[start:i].strip())
            start = i + 1
        i += 1
    tail = text[start:].strip()
    if tail:
        pieces.append(tail)
    return pieces


def read_call_arguments(source, open_paren):
    """返回 (从 '(' 到配对 ')' 之间的实参文本, ')' 的下标)。"""
    depth = 0
    i = open_paren
    while i < len(source):
        char = source[i]
        if char in "\"'":
            quote = char
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                    continue
                i += 1
                if source[i - 1] == quote:
                    break
            continue
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[open_paren + 1 : i], i
        i += 1
    fail("无法解析调用实参：源码括号不平衡")
    return "", -1


def literal_fragments(piece):
    """片段若只是字符串字面量（可用 + 拼接），返回 (还原值, 各字面量片段)；否则 (None, [])。"""
    if not piece.startswith('"'):
        return None, []
    chunks, fragments = [], []
    for part in INDENTED_CONCAT.split(piece):
        match = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', part.strip(), re.S)
        if not match:
            return None, []
        value = unescape_java(match.group(1))
        chunks.append(value)
        fragments.append(value)
    return "".join(chunks), fragments


def scan_java(path):
    """抽取 [(key, fallback, 动态实参个数)]、所有含中文字面量片段、所有 fallback 片段。"""
    if not path.is_file():
        fail("缺少源码文件 %s" % path)
        return [], [], []
    source = strip_comments(path.read_text(encoding="utf-8"))
    pairs = []
    fallback_fragments = []

    for call in ("localized", "timeChange"):
        for match in re.finditer(r"\b" + call + r"\s*\(", source):
            inner, close = read_call_arguments(source, match.end() - 1)
            # 方法声明（参数表后面直接跟 '{'）不是调用点，跳过
            tail = source[close + 1 :].lstrip() if close >= 0 else ""
            if tail.startswith("{"):
                continue
            pieces = split_arguments(inner)
            if call == "timeChange":
                pieces = pieces[1:]  # 去掉 source 实参
                if len(pieces) != 3:
                    fail("%s: timeChange(...) 期望 (source, key, fallback, write)，实参数量不符" % path.name)
                    continue
                key, fragments = literal_fragments(pieces[0])
                fallback, fallback_piece_fragments = literal_fragments(pieces[1])
                if key is None or fallback is None:
                    fail("%s: timeChange(...) 的 key/fallback 必须是字面量" % path.name)
                    continue
                fallback_fragments.extend(fallback_piece_fragments)
                pairs.append((key, fallback, 1))  # timeChange 内部固定追加 1 个原因实参
                continue

            if len(pieces) < 2:
                fail("%s: localized(...) 至少要有 key 与 fallback 两个实参" % path.name)
                continue
            key, _ = literal_fragments(pieces[0])
            fallback, fallback_piece_fragments = literal_fragments(pieces[1])
            dynamic = pieces[2:]
            if key is None or fallback is None:
                # localized(failureKey, failureFallback, reason) —— 由 timeChange 的调用点负责校验
                if len(dynamic) != 1:
                    fail("%s: 间接 localized 调用应当只有 1 个动态实参，实际 %d" % (path.name, len(dynamic)))
                continue
            fallback_fragments.extend(fallback_piece_fragments)
            pairs.append((key, fallback, len(dynamic)))

    literal_uses = len(re.findall(r"Component\.literal\(", source))
    if literal_uses != EXPECTED_LITERAL_BRIDGES:
        fail(
            "%s: Component.literal 出现 %d 次，应为 %d 次（只允许 success/failure 的 String 桥接；"
            "其余反馈都要走 localized）" % (path.name, literal_uses, EXPECTED_LITERAL_BRIDGES)
        )
    if "Component.translatableWithFallback" not in source:
        fail("%s: 找不到 Component.translatableWithFallback" % path.name)

    cjk_literals = [
        unescape_java(match.group(1))
        for match in re.finditer(r'"((?:[^"\\]|\\.)*)"', source, re.S)
        if CJK.search(unescape_java(match.group(1)))
    ]
    return pairs, cjk_literals, [fragment for fragment in fallback_fragments if CJK.search(fragment)]


# ────────────────────────────── 语言文件 ──────────────────────────────


def read_lang(path):
    if not path.is_file():
        fail("缺少语言文件 %s" % path)
        return {}
    raw = path.read_text(encoding="utf-8")
    seen = []

    def hook(items):
        for key, _ in items:
            seen.append(key)
        return dict(items)

    try:
        data = json.loads(raw, object_pairs_hook=hook)
    except json.JSONDecodeError as error:
        fail("%s: JSON 解析失败：%s" % (path, error))
        return {}
    for key in sorted({key for key in seen if seen.count(key) > 1}):
        fail("%s: 键 %s 重复定义" % (path, key))
    for key, value in data.items():
        if not isinstance(value, str):
            fail("%s: 键 %s 的值不是字符串" % (path, key))
        elif not value.strip():
            fail("%s: 键 %s 的值是空的" % (path, key))
    return data


def placeholder_count(pattern):
    """占位符个数：``%s`` 按出现次数，``%n$s`` 取最大序号；混用返回 None。"""
    matches = PLACEHOLDER.findall(pattern)
    indexed = [match for match in matches if match]
    plain = [match for match in matches if not match]
    if indexed and plain:
        return None
    if indexed:
        return max(int(match[:-1]) for match in indexed)
    return len(plain)


# ────────────────────────────── 主流程 ──────────────────────────────


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    print("[locales] project = %s" % root)

    langs = {}
    pairs_by_variant = {}
    for name, sub in VARIANTS:
        base = (root / sub).resolve()
        langs[name] = {code: read_lang(base / LANG_DIR / (code + ".json")) for code in LANGS}
        pairs, cjk_literals, fallback_fragments = scan_java(base / SERVER_CLASS)
        pairs_by_variant[name] = pairs
        print("[locales] %-6s java: %d localized keys, %d Chinese fallback fragments"
              % (name, len(pairs), len(fallback_fragments)))

        # 7) 中文字面量片段必须与 fallback 片段完全对应（多一个 = 有文案绕开本地化）
        if Counter(cjk_literals) != Counter(fallback_fragments):
            for fragment, count in (Counter(cjk_literals) - Counter(fallback_fragments)).items():
                fail("%s: 含中文字面量没有走 localized：%r（%d 处）" % (name, fragment, count))
            for fragment, count in (Counter(fallback_fragments) - Counter(cjk_literals)).items():
                fail("%s: fallback 片段 %r 在源码里找不到对应字面量" % (name, fragment))

        # 1)/2)/3) 键存在、zh 与 fallback 一致、占位符三方一致
        for key, fallback, java_count in pairs:
            if not key.startswith("command.skyboxblock.server."):
                fail("%s: 键 %s 不在 command.skyboxblock.server.* 命名空间" % (name, key))
            zh = langs[name]["zh_cn"].get(key)
            en = langs[name]["en_us"].get(key)
            if zh is None:
                fail("%s: zh_cn 缺少键 %s" % (name, key))
            elif zh != fallback:
                fail("%s: zh_cn[%s]=%r 与 Java fallback=%r 不一致（中文原文必须原样入库）"
                     % (name, key, zh, fallback))
            if en is None:
                fail("%s: en_us 缺少键 %s" % (name, key))
            for label, value in (("zh_cn", zh), ("en_us", en)):
                if value is None:
                    continue
                count = placeholder_count(value)
                if count is None:
                    fail("%s: %s[%s] 混用了 %%s 与 %%n$s 占位符" % (name, label, key))
                elif count != java_count:
                    fail("%s: %s[%s] 有 %d 个占位符，Java 传了 %d 个实参"
                         % (name, label, key, count, java_count))

    # 4) 每个工程 en_us 与 zh_cn 键集一致
    for name in langs:
        en_keys = set(langs[name]["en_us"])
        zh_keys = set(langs[name]["zh_cn"])
        for key in sorted(en_keys - zh_keys):
            fail("%s: zh_cn 缺少 en_us 里有的键 %s" % (name, key))
        for key in sorted(zh_keys - en_keys):
            fail("%s: en_us 缺少 zh_cn 里有的键 %s" % (name, key))

    # 5) 两个工程的键集与取值完全一致
    for code in LANGS:
        left = langs["1.21.1"][code]
        right = langs["26.2"][code]
        for key in sorted(set(left) - set(right)):
            fail("%s: mc26.2 缺少根工程里有的键 %s" % (code, key))
        for key in sorted(set(right) - set(left)):
            fail("%s: 根工程缺少 mc26.2 里有的键 %s" % (code, key))
        for key in sorted(set(left) & set(right)):
            if left[key] != right[key]:
                fail("%s: 键 %s 两个工程取值不同：%r / %r" % (code, key, left[key], right[key]))

    # 两个版本的 Java 必须引用同一批键
    keys_left = {key for key, _, _ in pairs_by_variant["1.21.1"]}
    keys_right = {key for key, _, _ in pairs_by_variant["26.2"]}
    for key in sorted(keys_left - keys_right):
        fail("26.2 的 Java 没有使用 %s（两个版本必须一致）" % key)
    for key in sorted(keys_right - keys_left):
        fail("1.21.1 的 Java 没有使用 %s（两个版本必须一致）" % key)

    # 6) 没有孤儿键
    used = keys_left | keys_right
    for name in langs:
        for code in LANGS:
            for key in sorted(langs[name][code]):
                if key.startswith("command.skyboxblock.server.") and key not in used:
                    fail("%s/%s: 键 %s 不被任何源码引用（孤儿键）" % (name, code, key))

    print("[locales] keys: root en_us=%d zh_cn=%d, 26.2 en_us=%d zh_cn=%d"
          % (len(langs["1.21.1"]["en_us"]), len(langs["1.21.1"]["zh_cn"]),
             len(langs["26.2"]["en_us"]), len(langs["26.2"]["zh_cn"])))
    print("[locales] server command keys used by Java: %d (1.21.1) / %d (26.2)" % (len(keys_left), len(keys_right)))

    if failures:
        print("[locales] FAILED (%d)" % len(failures))
        for failure in failures:
            print("  - " + failure)
        return 1
    print("[locales] PASS: 键集/中文原文/占位符/跨版本一致性全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
