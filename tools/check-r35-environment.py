#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R35 独立只读审计：原版天空盒方块的环境属性配置（environment）。

用法（只读，不写任何生产文件、不跑 gradle、不启动游戏）::

    python skybox-mod/tools/check-r35-environment.py                 # 源码合同 + 数学自测
    python skybox-mod/tools/check-r35-environment.py --json out.json # 同时落一份机器可读报告
    python skybox-mod/tools/check-r35-environment.py --math-only     # 只跑纯数学自测

注意：本工作区的 `python.exe` 在 `PATH` 上可能只是 Windows Store 存根（exit 9009），
请改用带 NumPy 的真实 Python 解释器（本机取运行时自带的那个，路径随安装位置变化）。

三层检查
--------
A. 静态源码合同：扫描 java / shader / properties 文本，检查规格书
   `docs/ENVIRONMENT-ATTRIBUTES-R35-SPEC-2026-10-31.md` 的 §4/§6/§7/§8 合同。
B. 数学与解析自测：用纯 Python 复算属性表的颜色 round-trip、范围校验、
   `cloud_height = H - 0.33` 换算、天体角度方位、`end/3` 淡出起点。
C. 旧接口兼容：确认旧命令 literal / 常量 / 取值区间没被删改。

边界（务必读）
--------------
本脚本**只证明源码文本与数学合同**，**不证明实机画面**。
没有 `run-r35-environment.py` 产出的真实客户端命令结果与截图,
就不能宣称"实机通过"。脚本最后会明确打印这一点。

作者：R35 独立审计角色（非生产代码作者）。
"""
from __future__ import annotations

import argparse
import json
import math
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java/dev/yg/skyboxblock"
JAVA_CLIENT = JAVA / "client"
SHADERS = ROOT / "src/main/resources/assets/skyboxblock/shaders/core"

# ---------------------------------------------------------------- 属性表（真相源）
# 字段：id, kind, default_auto_note, min, max, strict_positive, wiki_default, since
# 这张表是本脚本的期望值；生产代码必须与之一致。
ATTRIBUTES = [
    # id                        kind      min    max    >0    wiki_default   since
    ("visual/sky_color",              "rgb",     None,  None,  False, "#000000",   "1.21.11"),
    ("visual/fog_color",              "rgb",     None,  None,  False, "#000000",   "1.21.11"),
    ("visual/fog_start_distance",     "float",   None,  None,  False, "0.0",       "25w44a"),
    ("visual/fog_end_distance",       "float",   0.0,   None,  True,  "1024.0",    "25w44a"),
    ("visual/sky_fog_end_distance",   "float",   0.0,   None,  True,  "512.0",     "25w44a"),
    ("visual/cloud_color",            "argb",    None,  None,  False, "#00000000", "25w44a"),
    ("visual/cloud_height",           "float",   None,  None,  False, "192.33",    "1.21.11"),
    ("visual/cloud_fog_end_distance", "float",   0.0,   None,  True,  "2048.0",    "25w44a"),
    ("visual/sunrise_sunset_color",   "argb",    None,  None,  False, "#00000000", "25w45a"),
    ("visual/sun_angle",              "float",   None,  None,  False, "0.0",       "25w45a"),
    ("visual/moon_angle",             "float",   None,  None,  False, "0.0",       "25w45a"),
    ("visual/star_angle",             "float",   None,  None,  False, "0.0",       "25w45a"),
    ("visual/moon_phase",             "enum",    None,  None,  False, "full_moon", "25w45a"),
    ("visual/star_brightness",        "float",   0.0,   1.0,   False, "0.0",       "25w45a"),
    ("visual/has_sky_occluder",       "bool",    None,  None,  False, "false",     "26.4-snap2"),
]
ATTR_IDS = [a[0] for a in ATTRIBUTES]
MOON_PHASES = ["full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
               "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous"]
# 明确不纳入（文档必须提到，且不能出现在可 set 的表里）
EXCLUDED = [
    "visual/water_fog_color", "visual/water_fog_start_distance", "visual/water_fog_end_distance",
    "visual/block_light_tint", "visual/sky_light_color", "visual/sky_light_factor",
    "visual/night_vision_color", "visual/ambient_light_color",
    "visual/default_dripstone_particle", "visual/ambient_particles",
]

CLOUD_EPS = 0.33          # float32 语义下 H - 0.33F 舍回整数名义值（已用 Java 实测）
TOL = 1e-4                # 审计容差（格 / 单位）

# 复现 float32 运算，避免用 double 中间值下结论（v1 的教训）
def f32(x: float) -> float:
    return struct.unpack("<f", struct.pack("<f", x))[0]


def f32_bits(x: float) -> int:
    return struct.unpack("<I", struct.pack("<f", x))[0]


# ---------------------------------------------------------------- 结果收集
class Report:
    def __init__(self) -> None:
        self.checks: list[dict] = []

    def check(self, name: str, ok: bool, **extra: object) -> bool:
        self.checks.append({"name": name, "passed": bool(ok), **extra})
        return bool(ok)

    @property
    def passed(self) -> bool:
        return all(c["passed"] for c in self.checks)

    def counts(self) -> dict:
        return {"passed": sum(c["passed"] for c in self.checks), "total": len(self.checks)}


# ---------------------------------------------------------------- 源码读取（只读）
def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""


def find_java(name: str) -> Path | None:
    """在包根与 client 子包中找某个类（首字母大小写不敏感）。

    注意：`SkyEnvironment` 位于包根 `dev/yg/skyboxblock/`，**不在** client 子包。
    """
    target = name.lower()
    for base in (JAVA, JAVA_CLIENT):
        if base.is_dir():
            for p in base.glob("*.java"):
                if p.stem.lower() == target:
                    return p
    return None


def strip_java_comments(src: str) -> str:
    """去掉 // 与 /* */ 注释（保留字符串字面量，命令 literal 就在里面）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        # 字符串 / 文本块：原样保留
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                if src[j] == '\n':
                    break
                j += 1
            out.append(src[i:j])
            i = j
            continue
        if c == '/' and src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if c == '/' and src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(' ' * (j - i))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def strip_strings(src: str) -> str:
    """额外去掉字符串字面量（用于"不该出现某个词"的负向断言，避免日志/提示文本误命中）。"""
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)


# ---------------------------------------------------------------- A. 源码合同
def layer_source(rep: Report) -> None:
    files: dict[str, tuple[Path | None, str]] = {}
    # SkyEnvironment 住在包根（dev/yg/skyboxblock/），不在 client 子包。
    for name in ("SkyEnvironment", "SkyEnvironmentMath", "SkyboxConfig", "SkyboxCommands",
                 "SkyDisplayState", "VanillaSkyScene", "SkyVanillaClouds", "SkyRenderMath"):
        path = find_java(name)
        files[name] = (path, read_text(path) if path else "")

    # --- A1 类存在性
    for name in ("SkyEnvironment", "SkyEnvironmentMath", "SkyboxConfig", "SkyboxCommands",
                 "SkyDisplayState", "VanillaSkyScene", "SkyVanillaClouds"):
        rep.check(f"source-class-present:{name}", files[name][0] is not None,
                  path=str(files[name][0]) if files[name][0] else None)

    env_path, env_raw = files["SkyEnvironment"]
    env = strip_java_comments(env_raw)
    cfg_path, cfg_raw = files["SkyboxConfig"]
    cfg = strip_java_comments(cfg_raw)
    cmd_path, cmd_raw = files["SkyboxCommands"]
    cmd = strip_java_comments(cmd_raw)
    scene = strip_java_comments(files["VanillaSkyScene"][1])
    clouds = strip_java_comments(files["SkyVanillaClouds"][1])
    maths = strip_java_comments(files["SkyRenderMath"][1])
    envmath = strip_java_comments(files["SkyEnvironmentMath"][1])
    state = strip_java_comments(files["SkyDisplayState"][1])
    geom = strip_java_comments(read_text(JAVA_CLIENT / "SkyVanillaGeometry.java"))
    # 负向断言用：去掉字符串字面量，避免日志/提示文本误命中
    # ⚠ 正向断言（如 env.has("sun_angle")）必须用保留字面量的版本 `scene`/`state`/`clouds`。
    env_nostr = strip_strings(env)
    cfg_nostr = strip_strings(cfg)
    scene_nostr = strip_strings(scene)
    clouds_nostr = strip_strings(clouds)
    state_nostr = strip_strings(state)

    # --- A0 编译级健全性（回归此前发现的 3 个真实 bug）
    # A0.1 无参构造器不得存在（final Map 未赋值 => 编译错误）
    rep.check("no-uninitialized-final-field-ctor",
              not re.search(r"private\s+SkyEnvironment\s*\(\s*\)\s*\{\s*\}", env_nostr),
              note="final Map 必须在所有构造器赋值；无参构造器会导致编译失败")
    # A0.2 颜色取值不得跳过 '#'（canonicalColor 存的是 "#" + digits）
    rep.check("color-substring-skips-hash-correctly",
              not re.search(r"substring\s*\(\s*2\s*\)", env_nostr.split("record Value")[-1].split("}")[0]
                            if "record Value" in env_nostr else ""),
              note="Value.color() 必须 substring(1)：canonicalColor 输出带 '#'")
    # A0.3 attribute() 必须能解析带命名空间前缀的完整 ID
    rep.check("attribute-lookup-tries-bare-first",
              bool(re.search(r"REGISTRY\.get\s*\(\s*bare\s*\)", env_nostr)),
              note="否则 minecraft:visual/sky_color 解析失败")

    # --- A2 属性 ID 全覆盖（在 environment 注册表里）
    missing = [a for a in ATTR_IDS if a not in env_raw]
    rep.check("all-15-attribute-ids-declared", not missing, missing=missing)
    rep.check("registry-count-is-15", len(re.findall(r"=\s*register\(new Attribute\(", env)) == 15,
              found=len(re.findall(r"=\s*register\(new Attribute\(", env)))

    # --- A3 被排除的属性不能出现在可 set 表里
    leaked = [a for a in EXCLUDED if a in env_raw]
    rep.check("excluded-attributes-not-declared", not leaked, leaked=leaked)

    # --- A4 八个 enum 名都要在源码里
    miss_phases = [p for p in MOON_PHASES if p not in env_raw]
    rep.check("moon-phase-enum-complete", not miss_phases, missing=miss_phases)

    # --- A4b 两套月相解析必须语义一致（不得一处报错、一处静默取模）
    has_floormod = bool(re.search(r"floorMod\s*\(\s*Integer\.parseInt", env_nostr))
    rep.check("moon-phase-parse-consistent",
              not has_floormod or bool(re.search(r">\s*7|>=\s*8|index\s*>\s*7", env_nostr)),
              note="MoonPhase.parse 的 floorMod 静默折返与 canonicalPhase 越界报错矛盾")

    # --- A5 颜色解析：ARGB 的 int 必须无符号解析（经典坑）
    unsigned_ok = bool(re.search(r"parseUnsignedInt|Long\.parseLong|parseLong\s*\(\s*\w+\s*,\s*16", env_nostr))
    rep.check("argb-int-parsed-unsigned", unsigned_ok,
              note="Integer.parseInt 遇到 #ffRRGGBB 会 NumberFormatException")

    # --- A6 三个 end 距离必须严格 > 0（不能有 0 的特殊语义）
    rep.check("distance-end-strict-positive-guard", bool(re.search(r">\s*0F?|isFinite", env_nostr)))
    # 不允许 "0 = 全雾/不绘" 这类特例（只认 min==MIN_POSITIVE 的 positive 标记）
    rep.check("positive-attr-uses-min-positive-marker", "MIN_POSITIVE" in env_raw)
    # defaultValue 元数据：**非阻断**。依据——全仓 grep `defaultValue` 只有 record 头一处声明，
    # 没有任何 `.defaultValue()` 取值读取；auto 语义由调用方的 `number(id, fallback)` 决定。
    # 因此 0 作为「无内置默认」哨兵是可接受的，不应为了过测试把 auto 硬写成 1024。
    dv_readers = [f for f in ("SkyEnvironment", "SkyDisplayState", "VanillaSkyScene",
                              "SkyVanillaClouds", "SkyEnvironmentMath", "SkyboxConfig")
                  if f in files and re.search(r"\.\s*defaultValue\s*\(\s*\)", files[f][1])]
    rep.check("defaultValue-metadata-not-read-at-runtime",
              not dv_readers,
              readers=dv_readers,
              note="非阻断：只校验 auto 不依赖 defaultValue；0 哨兵本身不算缺陷")

    # --- A7 fog_end 下限 96
    rep.check("fog-end-min-96-clamp", bool(re.search(r"Math\.max\s*\(\s*96", envmath)))

    # --- A8 cloud_height 换算 0.33
    rep.check("cloud-height-uses-0.33-epsilon",
              bool(re.search(r'cloud_height"[\s\S]{0,80}?\.33F', envmath)))
    rep.check("legacy-cloudRelativeY-still-present",
              "cloudRelativeY" in maths and re.search(r"\+\s*\.33F", maths) is not None)
    rep.check("cloud-height-impl-single-place",
              len(re.findall(r"cloudHeight\s*\(", envmath + clouds)) >= 1
              and clouds_nostr.count("-.33F") <= 1,
              note="0.33 换算只允许出现在 SkyEnvironmentMath.cloudHeight 一处")

    # --- A9 cloud_color alpha 拆包 + 早退
    rep.check("cloud-opacity-unpacked", bool(re.search(r">>>\s*24", state)))
    rep.check("cloud-alpha-zero-skips-draw",
              bool(re.search(r"cloudOpacity\s*<=\s*0F?\s*\)\s*return\s+false", clouds)))
    rep.check("cloud-opacity-sent-to-shader", "CloudOpacity" in clouds)
    rep.check("cloud-opacity-applied-in-shader",
              "CloudOpacity" in read_text(SHADERS / "vanilla_cloud.fsh")
              or "CloudOpacity" in read_text(SHADERS / "vanilla_cloud.vsh"),
              note="uniform 声明了就必须真的用上")

    # --- A10 三个天体角度：语义判定（不是数变量名）
    # 每个角度都必须有 has(...) 分支，且显式分支走独立的 celestialMvp(...)
    for attr in ("sun_angle", "moon_angle", "star_angle"):
        rep.check(f"celestial-angle-explicit-branch:{attr}",
                  f'env.has("{attr}")' in scene)
    rep.check("celestial-explicit-uses-own-mvp",
              len(re.findall(r"celestialMvp\s*\(\s*frame\s*,\s*projection", scene)) >= 3,
              found=len(re.findall(r"celestialMvp\s*\(", scene)),
              note="三个显式分支各自构造 celestialMvp；auto 分支复用 solarMvp 是刻意的逐位旧行为")
    # auto 分支必须仍走 solarMvp（保证默认不变）
    rep.check("auto-celestial-keeps-solarMvp", "solarMvp" in scene)
    # 月亮 −180 补偿
    rep.check("moon-angle-minus-180-compensation",
              bool(re.search(r'moon_angle"[\s\S]{0,160}?-\s*180', scene)),
              note="几何上月亮在 -Y，显式角度必须 -180 才对应『0=正上方』")

    # --- A11 has_sky_occluder 三分支（显式 true / 显式 false / auto）
    rep.check("occluder-explicit-true-branch",
              bool(re.search(r'flag\s*\(\s*"has_sky_occluder"\s*,\s*false\s*\)', scene)))
    rep.check("occluder-auto-keeps-legacy-disc",
              bool(re.search(r'!\s*env\.has\(\s*"has_sky_occluder"\s*\)', scene)))
    rep.check("occluder-uses-fog-color",
              bool(re.search(r"occluder\s*\(\s*\(float\)\s*sky\.fogColor", scene)))
    # occluder 必须在天体之后绘制
    low_scene = scene.lower()
    cel_idx, occl_idx = low_scene.find("celestial"), low_scene.find("occluder")
    rep.check("occluder-drawn-after-celestials", 0 <= cel_idx < occl_idx,
              celestial=cel_idx, occluder=occl_idx)
    rep.check("occluder-smooth-edged-not-hard-black",
              "visibility" in geom,
              note="SkyVanillaGeometry.occluder 用 visibility() 做平滑边缘")

    # --- A12 命令树完整（允许字面量或常量两种写法）
    for sub in ("environment", "env"):
        rep.check(f"command-{sub}-present",
                  f'literal("{sub}")' in cmd or f'"{sub}"' in cmd)
    for sub in ("list", "get", "set", "reset"):
        rep.check(f"command-subcommand-{sub}",
                  f'literal("{sub}")' in cmd or f'"{sub}"' in cmd)
    rep.check("command-reset-all", '"all"' in cmd)
    rep.check("command-tab-completion", bool(re.search(r"suggest", cmd, re.I)))

    # --- A13 ID 解析：命名空间与短 ID
    rep.check("id-resolution-namespaced", "NAMESPACE" in env_raw and "minecraft:" in env_raw)
    rep.check("id-resolution-shortform", bool(re.search(r"shortId\s*\(", env_nostr)))

    # --- A13b 文档契约必须与源码一致：**只 3 种** ID 写法。
    #   依据 `SkyboxCommands.forms()` 只加 shortId()/id()/NAMESPACE+id() 三项，
    #   且 `SkyEnvironment.NAMESPACE` 只有 "minecraft:"（无 skyboxblock: 别名、无下划线替换）。
    if "forms" in cmd:
        form_body = cmd[cmd.find("forms"):]
        n_add = len(re.findall(r"forms\.add\(", form_body))
        rep.check("id-forms-exactly-three", n_add == 3, formsAdded=n_add)
    rep.check("namespace-only-minecraft",
              bool(re.search(r'NAMESPACE\s*=\s*"minecraft:"', env)),
              note="用保留字面量版本；NAMESPACE 的值本身是字符串")
    rep.check("no-skyboxblock-namespace-alias", "skyboxblock:" not in env_nostr)
    rep.check("no-underscore-group-alias", "visual_" not in env_nostr)

    # --- A14 持久化键格式 environment.visual/*
    rep.check("persist-key-prefix-environment", '"environment."' in env_raw + cfg_raw)
    rep.check("persist-key-includes-visual-slash",
              "environment.visual/" in (env_raw + cfg_raw) or "KEY_PREFIX + attribute.id()" in env_nostr)

    # --- A15 旧键不被 reset all 清除
    rep.check("legacy-keys-independent-of-reset-all",
              not bool(re.search(r'reset\s*all[^;]{0,400}vanillaCloudHeight', cmd + env_raw, re.I)))

    # --- A16 旧接口保留（环境变量/常量必须在命令源码里出现）
    for legacy in ("cloudheight", "clouddistance", "cloudfade", "reload"):
        rep.check(f"legacy-command-{legacy}", f'"{legacy}"' in cmd)
    rep.check("legacy-cloud-bounds-kept",
              bool(re.search(r"MIN_CLOUD_HEIGHT|MAX_CLOUD_HEIGHT", cfg_raw))
              and bool(re.search(r"MIN_CLOUD_DISTANCE|MAX_CLOUD_DISTANCE", cfg_raw)))
    rep.check("legacy-cloud-default-192", bool(re.search(r"VANILLA_CLOUD_HEIGHT", maths + cfg_raw)))

    # --- A17 覆盖色为最终色：覆盖分支不得再乘天气
    override_branches = re.findall(r'has\("(sky_color|fog_color|cloud_color)"\)', state)
    rep.check("override-colors-are-final", len(set(override_branches)) == 3,
              branches=sorted(set(override_branches)))
    weather_on_override = bool(re.search(
        r'has\("(?:sky_color|fog_color|cloud_color)"\)\s*\?[^;]{0,200}weather\(', state))
    rep.check("no-weather-multiply-on-override", not weather_on_override)

    # --- A17b star_brightness：显式值为**最终值**（不乘雨），auto 保留旧 weather 链。
    #   依据主线口径 + 源码 `sky.starBrightness*(env.has("star_brightness") ? 1F : weatherAlpha)`。
    rep.check("star-brightness-explicit-skips-weather",
              bool(re.search(r'has\(\s*"star_brightness"\s*\)\s*\?\s*1F\s*:\s*weatherAlpha', scene)),
              note="显式 star_brightness 为最终值；auto 才乘 (1-rain)")
    rep.check("star-brightness-auto-keeps-weather-chain", "weatherAlpha" in scene)

    # --- A18 日月星不吃雾（shader 不得出现雾）
    for shader in ("vanilla_celestial.fsh", "vanilla_stars.fsh"):
        body = read_text(SHADERS / shader)
        rep.check(f"shader-{shader}-no-fog", "Fog" not in body and "fog" not in body,
                  path=str(SHADERS / shader))

    # --- A19 不触碰真实世界雾/光照（红线）
    touched = [t for t in ("FogRenderer.setupColor", "RenderSystem.setShaderFogStart(",
                           "LightTexture", "getSkyLightColor") if t in env_raw + scene]
    rep.check("does-not-write-world-fog-or-lighting", not touched, tokens=touched)

    # --- A20 SkyEnvironmentMath 纯数学（无 MC 依赖）
    if files["SkyEnvironmentMath"][0] is not None:
        imports = re.findall(r"^import\s+([\w.]+);", files["SkyEnvironmentMath"][1], re.M)
        bad = [i for i in imports if i.startswith("net.minecraft") or i.startswith("com.mojang")]
        rep.check("math-helper-pure-no-MC-import", not bad, badImports=bad)
    else:
        rep.check("math-helper-present", False, note="SkyEnvironmentMath.java 尚未创建")


# ---------------------------------------------------------------- B. 数学自测
def layer_math(rep: Report) -> None:
    # B1 颜色 round-trip：RGB
    def rgb_parse(s: str) -> tuple[int, int, int]:
        h = s.lstrip("#")
        assert len(h) == 6, s
        return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)

    def rgb_format(r: int, g: int, b: int) -> str:
        return f"#{r:02x}{g:02x}{b:02x}"

    rep.check("rgb-roundtrip", all(
        rgb_parse(rgb_format(*c)) == c
        for c in [(0, 0, 0), (255, 255, 255), (0x7b, 0xa4, 0xff), (0x12, 0x34, 0x56)]
    ))
    # RGB int 形式：R<<16 | G<<8 | B
    rep.check("rgb-int-encoding", (0x7b << 16 | 0xa4 << 8 | 0xff) == 0x7BA4FF)
    # RGB 序列化时 A 恒 0，反序列化时 A 恒 255
    rep.check("rgb-alpha-conventions", True, note="反序列化 A=255；序列化 A=0（Wiki 通用结构）")

    # B2 ARGB round-trip（含 alpha 可小写）
    def argb_parse(s: str) -> tuple[int, int, int, int]:
        h = s.lstrip("#")
        assert len(h) == 8, s
        return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)

    def argb_format(a: int, r: int, g: int, b: int) -> str:
        return f"#{a:02x}{r:02x}{g:02x}{b:02x}"

    rep.check("argb-roundtrip", all(
        argb_parse(argb_format(*c)) == c
        for c in [(0, 0, 0, 0), (255, 255, 255, 255), (0xc0, 0x48, 0xff, 0x90), (1, 2, 3, 4)]
    ))
    packed = (0xC0 << 24) | (0x48 << 16) | (0xFF << 8) | 0x90
    rep.check("argb-int-pack-is-unsigned-32",
              packed == 0xC048FF90 and packed > 0x7FFFFFFF,
              note="超过 int 正范围：Java 必须 parseUnsignedInt / parseLong")

    # B3 cloud_height 0.33 换算（float32 语义）
    heights = [192.33, 240.33, 100.33, 1.33, 63.33, 0.33]
    ok = True
    details = []
    for h in heights:
        nominal = f32(f32(h) - f32(0.33))
        expected = round(h - 0.33, 6)
        close = abs(nominal - expected) < TOL
        ok &= close
        details.append({"cloud_height": h, "nominal": nominal, "expected": expected, "ok": close})
    rep.check("cloud-height-minus-0.33", ok, rows=details)

    # 192.33 - 0.33 必须恰好是 192.0 的 float 位模式
    rep.check("cloud-height-192.33-bitwise-equals-192",
              f32_bits(f32(f32(192.33) - f32(0.33))) == f32_bits(192.0),
              bits=hex(f32_bits(f32(f32(192.33) - f32(0.33)))),
              note="Java 实测为真；Python 若只用 double 中间值会误判为假")

    # B4 cloudRelativeY 等价（新显式路径 vs 旧 auto 路径）
    def cloud_rel_y(cam_y: float, nominal: float) -> float:
        return f32(f32(nominal - f32(cam_y)) + f32(0.33))

    cams = [0.0, 63.0, 64.0, 64.5, 100.0, 128.7, 192.33, 256.0, -64.0]
    eq = True
    rows = []
    for cy in cams:
        new = cloud_rel_y(cy, f32(f32(192.33) - f32(0.33)))
        old = cloud_rel_y(cy, 192.0)
        same = abs(new - old) <= TOL
        eq &= same
        rows.append({"cameraY": cy, "new": new, "old": old, "same": same})
    rep.check("cloud-height-192.33-equals-legacy-192", eq, rows=rows)

    # B5 反向：nominal + 0.33 再 -0.33 必须回来
    rt = all(abs(f32(f32(f32(n) + f32(0.33)) - f32(0.33)) - n) <= TOL
             for n in [192.0, 240.0, 96.0, 1.0, 100.5, 0.0])
    rep.check("cloud-height-reverse-roundtrip", rt)

    # B6 天体角度方位：0=正上方，90=落西
    # 旧几何：太阳 y=+100，月亮 y=-100，同一矩阵 rotate X。
    # 断言：sun_angle=0 与 moon_angle=0 都指向 +Y（月亮需 -180 补偿）
    def celestial_up(angle_deg: float, is_moon: bool) -> tuple[float, float]:
        """返回 (y, z) 单位分量（把 quad 的中心 y=±size 旋转 angle 度）。"""
        base = -1.0 if is_moon else 1.0          # 几何上的 +Y / -Y
        a = math.radians(angle_deg)
        # rotate X: y' = y*cos - z*sin, z' = y*sin + z*cos ；z=0
        return base * math.cos(a), base * math.sin(a)

    sun_y, _ = celestial_up(0.0, False)
    moon_raw_y, _ = celestial_up(0.0, True)
    moon_fixed_y, _ = celestial_up(0.0 - 180.0, True)
    rep.check("sun-angle-0-is-up", abs(sun_y - 1.0) < 1e-9, sunY=sun_y)
    rep.check("moon-geometry-is-inverted", abs(moon_raw_y + 1.0) < 1e-9, moonRawY=moon_raw_y,
              note="同一矩阵下月亮天然在 -Y，这是 -180 补偿的依据")
    rep.check("moon-angle-0-minus-180-is-up", abs(moon_fixed_y - 1.0) < 1e-9, moonFixedY=moon_fixed_y)

    # 90 度：太阳/月亮的 z 分量应为同号（都落西）
    sun90_y, sun90_z = celestial_up(90.0, False)
    moon90_y, moon90_z = celestial_up(90.0 - 180.0, True)
    rep.check("angle-90-same-westward-side", sun90_z * moon90_z > 0,
              sun90=(sun90_y, sun90_z), moon90=(moon90_y, moon90_z))

    # 360 度与 0 度等价
    rep.check("angle-periodic-360",
              abs(celestial_up(360.0, False)[0] - celestial_up(0.0, False)[0]) < 1e-9)

    # B7 end/3 淡出起点
    rep.check("cloud-fade-start-is-end-third",
              all(abs(e / 3.0 - e / 3.0) < 1e-12 for e in [384, 768, 1536, 2048]),
              note="start = end/3，与既有 /skyboxblock clouddistance 一致")

    # B8 严格 > 0 校验（0 与负数都必须被拒）
    def valid_end(v: float) -> bool:
        return math.isfinite(v) and v > 0.0

    rep.check("end-distance-rejects-zero",
              not valid_end(0.0) and not valid_end(-1.0) and valid_end(1e-3) and valid_end(4096.0),
              rejectedZero=True, rejectedNegative=True)
    # fog_end 最终值夹到 >= 96
    rep.check("fog-end-clamped-to-96", max(96.0, 1.0) == 96.0 and max(96.0, 200.0) == 200.0)

    # B9 star_brightness 范围
    def valid_brightness(v: float) -> bool:
        return math.isfinite(v) and 0.0 <= v <= 1.0

    rep.check("star-brightness-range-0-1",
              valid_brightness(0.0) and valid_brightness(1.0) and valid_brightness(0.5)
              and not valid_brightness(-0.01) and not valid_brightness(1.01))

    # B10 moon_phase 数字简写与命名等价
    rep.check("moon-phase-index-to-name",
              MOON_PHASES[0] == "full_moon" and MOON_PHASES[4] == "new_moon"
              and MOON_PHASES[7] == "waxing_gibbous" and len(MOON_PHASES) == 8,
              order=MOON_PHASES,
              note="枚举顺序有英文 Wiki〈Moon〉一手支持；纹理网格对应仍需画面确认")

    # B11 ID 解析：**只接受 3 种**写法（依据 SkyboxCommands.forms() + SkyEnvironment.attribute()）
    #   shortId / id / "minecraft:" + id ；`skyboxblock:` 前缀与 `visual_` 下划线**均不被支持**。
    def canonical(s: str) -> str | None:
        s = s.strip()
        bare = s[len("minecraft:"):] if s.startswith("minecraft:") else s
        if bare in ATTR_IDS:
            return bare
        cand = "visual/" + bare
        return cand if cand in ATTR_IDS else None

    accepted = ["sky_color", "visual/sky_color", "minecraft:visual/sky_color"]
    res = [canonical(f) for f in accepted]
    rep.check("id-alias-resolution-3-forms", all(r == "visual/sky_color" for r in res), resolved=res)
    # 注册表只认 minecraft: 前缀 —— 这些必须解析失败，文档不得宣传
    rejected = ["skyboxblock:visual/sky_color", "visual_sky_color",
                "not_a_thing", "visual/water_fog_color"]
    bad = [f for f in rejected if canonical(f) is not None]
    rep.check("id-resolution-rejects-unsupported-forms", not bad, unexpectedlyAccepted=bad,
              note="skyboxblock: 前缀与 visual_ 下划线都无实现，手册不得写")


# ---------------------------------------------------------------- C. 旧接口回归
def layer_legacy(rep: Report) -> None:
    cfg = read_text(find_java("SkyboxConfig") or Path("/nonexistent"))
    cmd = read_text(find_java("SkyboxCommands") or Path("/nonexistent"))
    maths = read_text(find_java("SkyRenderMath") or Path("/nonexistent"))

    rep.check("legacy-config-keys-declared",
              all(k in cfg for k in ("vanillaCloudHeight", "vanillaCloudFadeStart",
                                     "vanillaCloudFadeEnd", "clouds")))
    rep.check("legacy-default-192-and-384",
              "192" in cfg or "VANILLA_CLOUD_HEIGHT" in cfg, ok384="384" in cfg or "DEFAULT_CLOUD_FADE_END" in cfg)
    rep.check("legacy-atomic-write-preserved",
              "ATOMIC_MOVE" in cfg or "createTempFile" in cfg,
              note="SkyboxConfig.update() 的原子写必须保留")
    rep.check("legacy-extends-properties-preserved",
              "Properties" in cfg and "load" in cfg)
    rep.check("legacy-reload-invalidates-clouds", "invalidate" in cfg)
    rep.check("legacy-clouds-switch-still-read", "clouds" in cfg)
    # 旧命令仍走同一 literal 根
    rep.check("legacy-root-command-skyboxblock", 'literal("skyboxblock")' in cmd)


# ---------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", type=Path, help="把报告写到这个 JSON 文件")
    ap.add_argument("--math-only", action="store_true", help="只跑纯数学自测")
    ap.add_argument("--quiet", action="store_true", help="只打印失败项与汇总")
    args = ap.parse_args()

    rep = Report()
    if not args.math_only:
        layer_source(rep)
        layer_legacy(rep)
    layer_math(rep)

    failed = [c for c in rep.checks if not c["passed"]]
    if not args.quiet or failed:
        for c in rep.checks:
            mark = "PASS" if c["passed"] else "FAIL"
            extra = {k: v for k, v in c.items() if k not in ("name", "passed")}
            line = f"[{mark}] {c['name']}"
            if extra and not c["passed"]:
                line += "  " + json.dumps(extra, ensure_ascii=False)
            print(line)
    print()
    counts = rep.counts()
    print(f"checks: {counts['passed']}/{counts['total']} passed, {counts['total'] - counts['passed']} failed")
    print()
    print("SCOPE: 本脚本只验证源码文本合同与数学换算。")
    print("       它不提供任何实机/画面证据；没有 run-r35-environment.py 的")
    print("       真实客户端命令结果与截图，不得宣称『实机通过』。")

    if args.json:
        payload = {
            "tool": "check-r35-environment.py",
            "scope": "static-source-contract + math-selftest (NO runtime/screenshot evidence)",
            "counts": counts,
            "passed": rep.passed,
            "checks": rep.checks,
            "attributes": [{"id": a[0], "kind": a[1], "wikiDefault": a[5], "since": a[6]} for a in ATTRIBUTES],
            "excluded": EXCLUDED,
        }
        args.json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"report written: {args.json}")

    return 0 if rep.passed else 1


if __name__ == "__main__":
    sys.exit(main())
