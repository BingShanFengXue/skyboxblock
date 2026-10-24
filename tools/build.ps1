#!/usr/bin/env pwsh
# ─────────────────────────────────────────────────────────────────────────────
# Vehicle Mod (Fabric 1.21.1) 构建脚本 —— 本机专用
#
# 本机有两个必须固化的环境事实，否则构建会以难以理解的方式失败：
#
# 1) JDK 必须用 **微软构建的 21.0.3**（Minecraft 启动器自带的那份）。
#    Temurin 21.0.12.1 的 `Files.isWritable()` 走 Windows ACL 静态解析
#    (`WindowsFileSystemProvider.checkAccessMask`)，在本机 DSH 沙箱的 ACL 下
#    对**任何**路径都返回 false；而 Fabric Loom 的 `MinecraftJarMerger`
#    用它做前置校验，于是抛出 `java.nio.file.ReadOnlyFileSystemException`，
#    表现为「Failed to setup Minecraft」。微软 JDK 21.0.3 判定正常。
#    （同一台机器、同一目录、同一份代码，换 JDK 即通过，已实测。）
#
# 2) GRADLE_USER_HOME 必须指向工作区内的 .gradle-home。
#    默认的 ~/.gradle 在工作区沙箱下只读，Gradle 连 wrapper 锁文件都建不了。
#
# 3) 不要用 `--offline`：Loom 需要联网拉取 Minecraft 资源；
#    也不要加 `--no-daemon` 之外的额外参数，保持与已验证命令一致。
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
