#!/usr/bin/env pwsh
# ─────────────────────────────────────────────────────────────────────────────
# Vehicle Mod (Fabric 1.21.1) 构建脚本 —— 本机专用
#
# 【2026-11-14 修订】下面第 1 条已失效，保留结论供追溯。
#
# 1) JDK：用 Temurin 21（D:\Program Files\Eclipse Adoptium\jdk-21）即可。
#    历史上的错误做法：曾要求「必须用微软构建的 21.0.3（Minecraft 启动器自带）」，
#    理由是 Temurin 的 `Files.isWritable()` 走 Windows ACL 静态解析
#    (`WindowsFileSystemProvider.checkAccessMask`)，在当时的 DSH 沙箱 ACL 下
#    对任何路径都返回 false，Loom 的 `MinecraftJarMerger` 前置校验因此抛
#    `java.nio.file.ReadOnlyFileSystemException`，表现为「Failed to setup Minecraft」。
#    **该限制已经不存在**：当前沙箱为 danger-full-access，
#    实测 Temurin 21.0.13 对 build 目录 isWritable=true，`gradlew remapJar` EXIT=0。
#    Minecraft 启动器自带的那份 JDK 路径（旧 Administrator 配置文件下）也已不存在。
#    → 解析顺序仍保留「JAVA_HOME > tools/local.paths.json > MC 启动器运行时」，但不要再
#      把「必须微软 JDK」当成硬约束；找不到微软 JDK 不是失败原因。
#
# 2) GRADLE_USER_HOME 必须指向工作区内的 .gradle-home（约 1 GB 缓存）。
#    这是有效约束：默认的 ~/.gradle 可能有权限或锁问题，且缓存不在这里会全量重下。
#
# 3) 不要用 `--offline` 作为默认值：Loom 首次需要联网拉取 Minecraft 资源。
#    但**缓存已完整时 `--offline` 是可用的**，且比联网更快（实测 remapJar EXIT=0）。
#
# 4) 【最重要】1.21.1 必须走 `remapJar`，产物是 remap 到 intermediary 的。
#    曾发生过的事故（2026-11-14，1.0.8-test.2-html-1.21.1）：
#      - 当时沙箱 ACL 限制导致 Loom 跑不起来，于是改用 javac + tiny-remapper 手工拼装
#      - 手工流程没有正确处理 mixin，产出的 jar 里 Minecraft 类名仍是 Mojang 官方名
#        （`net/minecraft/world/level/ItemLike`），运行时直接
#        `NoClassDefFoundError: net/minecraft/world/level/ItemLike`
#      - 事故 jar 的 SHA256 D51559F6... 与 build/libs 完全相同，说明是直接拷贝构建产物
#    判别方法：正常构建会同时产出 build/libs/<name>.jar 和 build/devlibs/<name>-dev.jar
#    **成对**存在；只有单个文件 = 没走 remapJar。
#    部署前必须跑 tools/verify-remap.ps1 确认 named=0 / intermediary>0 / refmap 合法。
#
# 5) 26.1+（含本仓库的 mc26.2 子项目）不需要 remap。
#    Minecraft 26.1 起官方不再混淆，build.gradle 里没有任何 mappings 依赖，
#    jar 里用 `net.minecraft.world.*` 这类名字**是正确的**。
#    ⚠️ 不要把 26.2 的「无 mappings、无 remap」做法套用到 1.21.1 —— 那是两套规则。
# ─────────────────────────────────────────────────────────────────────────────
param(
    [string]$Task = 'build',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$proj = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $proj 'build.gradle'))) { $proj = $PSScriptRoot }

# JDK 解析顺序：环境变量 JAVA_HOME > tools\local.paths.json 的 java_home > Minecraft 启动器自带运行时。
# 仓库里不写死用户名或盘符。
function Resolve-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $cfg = Join-Path $PSScriptRoot 'local.paths.json'
    if (Test-Path -LiteralPath $cfg) {
        try {
            $j = Get-Content -LiteralPath $cfg -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($j.java_home -and (Test-Path (Join-Path $j.java_home 'bin\java.exe'))) { return [string]$j.java_home }
        } catch { }
    }
    if ($env:LOCALAPPDATA) {
        $runtime = Join-Path $env:LOCALAPPDATA 'Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime'
        if (Test-Path -LiteralPath $runtime) {
            $exe = Get-ChildItem -LiteralPath $runtime -Recurse -Depth 3 -Filter 'java.exe' -ErrorAction SilentlyContinue |
                   Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
            if ($exe) { return (Split-Path (Split-Path $exe.FullName -Parent) -Parent) }
        }
    }
    return $null
}

$env:JAVA_HOME = Resolve-Jdk
$env:GRADLE_USER_HOME = Join-Path $proj '.gradle-home'

if (-not $env:JAVA_HOME) {
    throw "找不到 JDK 21：设置 JAVA_HOME，或把 java_home 写进 tools\local.paths.json，或安装 Minecraft 启动器自带的 java-runtime。"
}
if (-not (Test-Path $env:GRADLE_USER_HOME)) {
    throw "找不到 Gradle home：$env:GRADLE_USER_HOME`n请先把 ~/.gradle 复制进来。"
}

Write-Host "JAVA_HOME        = $env:JAVA_HOME"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "project          = $proj"
Write-Host "task             = $Task"
Write-Host ('-' * 78)

Push-Location $proj
try {
    if ($Clean) {
        & '.\gradlew.bat' clean --no-daemon --console=plain
    }
    & '.\gradlew.bat' $Task --no-daemon --console=plain
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
