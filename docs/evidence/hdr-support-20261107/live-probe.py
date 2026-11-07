#!/usr/bin/env python3
"""后台实机探针：让真实客户端在**隐藏窗口、不抢焦点、不抓鼠标**的前提下启动到主菜单，
只为了读模组启动时对自定义天空盒素材的处理结果。

为什么只需要主菜单：
  CustomSkyRegistry.load() 由 ModInitializer.onInitialize 调用，发生在客户端初始化阶段，
  早于任何世界加载。因此不需要进世界、不需要存档，也不会触发 MouseHandler.grabMouse()。

窗口控制（按要求不影响桌面）：
  1. 进程一出现就轮询它自己的顶层窗口，拿到后立刻 ShowWindow(SW_HIDE)；
  2. 之后持续用 SetWindowPos(SWP_NOACTIVATE|SWP_HIDEWINDOW) 压制，防止任何激活；
  3. 26.2 生产包内的夹具 mixin 额外接管：GLFW_FOCUSED=false / GLFW_FOCUS_ON_SHOW=false /
     grabMouse 取消 / 每帧末尾 glfwHideWindow。
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT.parent
MC_ROOT = Path(r"D:\Program Files\Game\Minecraft\PCL\.minecraft")

USER32 = ctypes.WinDLL("user32", use_last_error=True)
EnumWindows = USER32.EnumWindows
EnumWindowsProc = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
GetWindowThreadProcessId = USER32.GetWindowThreadProcessId
ShowWindow = USER32.ShowWindow
SetWindowPos = USER32.SetWindowPos
GetWindowTextW = USER32.GetWindowTextW
IsWindowVisible = USER32.IsWindowVisible

SW_HIDE = 0
SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010
SWP_HIDEWINDOW = 0x0080


def windows_of(pid: int) -> list[int]:
    found: list[int] = []

    @EnumWindowsProc
    def callback(hwnd, _lparam):
        owner = wt.DWORD()
        GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid:
            found.append(hwnd)
        return True

    EnumWindows(callback, 0)
    return found


def hide_all(pid: int) -> list[str]:
    hidden = []
    for hwnd in windows_of(pid):
        ShowWindow(hwnd, SW_HIDE)
        SetWindowPos(hwnd, 0, 0, 0, 0, 0, SWP_NOZORDER | SWP_NOACTIVATE | SWP_HIDEWINDOW)
        title = ctypes.create_unicode_buffer(256)
        GetWindowTextW(hwnd, title, 256)
        hidden.append(f"{hwnd}:{title.value}")
    return hidden


def build_classpath(version_file: Path, version_dir: Path) -> list[str]:
    version = json.loads(version_file.read_text(encoding="utf-8"))

    def allowed(lib: dict) -> bool:
        rules = lib.get("rules")
        if not rules:
            return True
        ok = False
        for rule in rules:
            os_rule = rule.get("os", {})
            name = os_rule.get("name")
            if name is not None and name != "windows":
                continue
            arch = os_rule.get("arch")
            if arch is not None and arch not in ("x86_64", "amd64"):
                continue
            ok = rule.get("action") == "allow"
        return ok

    entries = []
    for lib in version.get("libraries", []):
        if not allowed(lib):
            continue
        artifact = lib.get("downloads", {}).get("artifact")
        if artifact and artifact.get("path"):
            entries.append(str(MC_ROOT / "libraries" / artifact["path"]))
            continue
        parts = lib.get("name", "").split(":")
        if len(parts) >= 3:
            group, artifact_name, ver = parts[0], parts[1], parts[2]
            classifier = parts[3] if len(parts) > 3 else None
            filename = f"{artifact_name}-{ver}" + (f"-{classifier}" if classifier else "") + ".jar"
            entries.append(str(MC_ROOT / "libraries" / Path(*group.split(".")) / artifact_name / ver / filename))
    entries.append(str(version_dir / f"{version_dir.name}.jar"))
    return entries


def prepare(case: Path, mod_jar: Path, source_version_dir: Path, config_text: str, materials: list[Path]):
    game = case / "game"
    if game.exists():
        shutil.rmtree(game)
    (game / "mods").mkdir(parents=True)
    # Fabric Loader 首次启动要把 MC jar 重映射到自己 gameDir 的 .fabric/remappedJars。
    # 该路径在本机不可写，因此直接复制原实例已经生成好的缓存（只读拷贝，不改原实例）。
    source_cache = source_version_dir / ".fabric"
    if source_cache.is_dir():
        shutil.copytree(source_cache, game / ".fabric")
    for jar in (source_version_dir / "mods").glob("*.jar"):
        low = jar.name.lower()
        if low.startswith("fabric-api-"):
            shutil.copy2(jar, game / "mods" / jar.name)
    shutil.copy2(mod_jar, game / "mods" / mod_jar.name)
    studio = game / "config" / "skyboxblock" / "skyboxes"
    studio.mkdir(parents=True, exist_ok=True)
    (studio / "10_hdr_case.conf").write_text(config_text, encoding="utf-8")
    for material in materials:
        shutil.copy2(material, studio / material.name)
    (game / "options.txt").write_text(
        "version:3955\nfullscreen:false\npauseOnLostFocus:false\nenableVsync:false\n"
        "maxFps:60\nlang:zh_cn\nrenderDistance:6\nsoundCategory_master:0.0\n"
        "resourcePacks:[]\nnarrator:0\nskipMultiplayerWarning:true\nonboardAccessibility:false\n",
        encoding="utf-8")
    return game


def run(label: str, java: str, version_dir: Path, game: Path, extra_jvm: list[str], timeout: int) -> dict:
    version_file = version_dir / f"{version_dir.name}.json"
    classpath = build_classpath(version_file, version_dir)
    missing = [p for p in classpath if not Path(p).is_file()]
    if missing:
        raise FileNotFoundError(missing[:5])
    natives = version_dir / f"{version_dir.name}-natives"
    # %LOCALAPPDATA%\Temp 在本机不可写（ImageIO 缓存文件创建失败会连带拖垮 Fabric 的 GUI 线程），
    # 因此把临时目录钉到本用例自己的目录里，绝不动系统级设置。
    scratch = game.parent / "tmp"
    scratch.mkdir(parents=True, exist_ok=True)
    args = [java, "-Xms1G", "-Xmx3G", *extra_jvm,
            f"-Djava.io.tmpdir={scratch}",
            f"-Djava.library.path={natives}",
            f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}",
            f"-Djna.tmpdir={natives}", f"-Dio.netty.native.workdir={natives}",
            "-Dminecraft.launcher.brand=hdr-probe",
            "-Dfabric.log.level=info",
            "-cp", os.pathsep.join(classpath),
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--username", "HdrProbe", "--version", label,
            "--gameDir", str(game), "--assetsDir", str(MC_ROOT / "assets"),
            "--assetIndex", "17" if "1.21.1" in version_dir.name else "32",
            "--uuid", "00000000000000000000000000000000", "--accessToken", "0",
            "--userType", "legacy", "--versionType", "release",
            "--width", "320", "--height", "200"]
    log_path = game.parent / "client.log"
    started = time.monotonic()
    hidden: list[str] = []
    with log_path.open("wb") as log:
        proc = subprocess.Popen(args, cwd=game, stdout=log, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        saw_registry = False
        try:
            while proc.poll() is None:
                if len(hidden) == 0 or time.monotonic() % 2 < 0.3:
                    new = hide_all(proc.pid)
                    for item in new:
                        if item not in hidden:
                            hidden.append(item)
                text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
                if "custom sky styles" in text or "图片无法解析" in text or "已跳过" in text:
                    saw_registry = True
                    # 再多等 3 秒让日志刷完，然后立即结束，避免占用机器。
                    time.sleep(3)
                    break
                if time.monotonic() - started > timeout:
                    break
                time.sleep(0.25)
        finally:
            hide_all(proc.pid)
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=30)
    elapsed = round(time.monotonic() - started, 1)
    text = log_path.read_text(encoding="utf-8", errors="replace")
    lines = [line for line in text.splitlines()
             if "skyboxblock" in line or "Failed to" in line or "EXCEPTION" in line]
    return {
        "label": label,
        "modJar": str(game / "mods"),
        "seconds": elapsed,
        "sawRegistryLog": saw_registry,
        "hiddenWindows": hidden,
        "modLines": lines,
        "log": str(log_path),
    }


def main() -> int:
    cases = json.loads((ROOT / "live-cases.json").read_text(encoding="utf-8"))
    results = []
    for case in cases:
        case_dir = ROOT / "live" / case["name"]
        case_dir.mkdir(parents=True, exist_ok=True)
        mod_jar = (WORKSPACE / case["mod"]).resolve(strict=True)
        version_dir = MC_ROOT / "versions" / case["version"]
        config_text = ("enable = true\n\ncase = {\n"
                       f'    id = "{case["id"]}"\n'
                       f'    picture = "{case["picture"]}"\n'
                       "    creative_inventory = false\n"
                       '    lang_zh_cn = { name = "HDR 探针" }\n}\n')
        materials = []
        for name in case["materials"]:
            candidate = ROOT / "materials" / name
            if candidate.exists():
                materials.append(candidate)
            else:
                hdr = WORKSPACE / "skyboxblock素材" / "04-定稿" / name
                if hdr.exists():
                    materials.append(hdr)
        game = prepare(case_dir, mod_jar, version_dir, config_text, materials)
        record = run(case["name"], case["java"], version_dir, game, case["jvm"], case.get("timeout", 240))
        results.append(record)
        print(json.dumps(record, ensure_ascii=False, indent=2), flush=True)
    (ROOT / "live-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
