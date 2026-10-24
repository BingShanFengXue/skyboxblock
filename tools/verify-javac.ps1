# 不跑 Gradle 的编译自验（供子智能体 / 审计使用）
#
# 原理：用 loom 导出的 compile-classpath.txt，把 src/main/java 整棵树复制到临时目录，
# 用 Microsoft JDK 21.0.3 的 javac 一次编译。
#
# 用法：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\verify-javac.ps1 -Tag audit
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\verify-javac.ps1 -Tag audit -Project "E:\other\proj"
param(
    [string]$Tag = 'verify',
    [string]$Project = ''
)

$ErrorActionPreference = 'Stop'

if (-not $Project) {
    # 脚本位于 <project>\tools\ 下
    $Project = Split-Path -Parent $PSScriptRoot
}
$Project = (Resolve-Path $Project).Path

$cpFile = Join-Path $Project 'build\compile-classpath.txt'
if (-not (Test-Path $cpFile)) {
    throw "缺少 $cpFile —— 先在项目根执行：.\\tools\\build.ps1 -Task dumpCp"
}

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

$jdk = Resolve-Jdk
if (-not $jdk) {
    throw "找不到 JDK 21：设置 JAVA_HOME，或把 java_home 写进 tools\local.paths.json。"
}
$javac = Join-Path $jdk 'bin\javac.exe'
if (-not (Test-Path $javac)) {
    throw "找不到 javac：$javac"
}

$srcRoot = Join-Path $Project 'src\main\java'
if (-not (Test-Path $srcRoot)) {
    throw "找不到源码目录：$srcRoot"
}

$work = Join-Path $Project ("build\verify-" + $Tag)
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$sources = Join-Path $work 'src'
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Force -Path $sources, $classes | Out-Null

# 稳定快照：复制前后各取一次指纹，避免复制到别人正在写的中间态
$copied = $false
for ($attempt = 1; $attempt -le 4; $attempt++) {
    $before = @(Get-ChildItem -Recurse -File (Join-Path $srcRoot '*.java') | Sort-Object FullName |
                ForEach-Object { $_.FullName + '|' + $_.Length + '|' + $_.LastWriteTimeUtc.Ticks })
    Copy-Item -Recurse (Join-Path $srcRoot '*') $sources -Force
    Start-Sleep -Milliseconds 120
    $after = @(Get-ChildItem -Recurse -File (Join-Path $srcRoot '*.java') | Sort-Object FullName |
               ForEach-Object { $_.FullName + '|' + $_.Length + '|' + $_.LastWriteTimeUtc.Ticks })
    if (($before -join "`n") -eq ($after -join "`n")) { $copied = $true; break }
}
if (-not $copied) { Write-Host '（源码快照不稳定，继续用最后一次复制结果）' -ForegroundColor Yellow }

# javac 的 @argfile 会把反斜杠当转义符，路径必须转正斜杠；classpath 走命令行
$argFile = Join-Path $work 'sources.txt'
$lines = Get-ChildItem -Recurse -File (Join-Path $sources '*.java') |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

$classPath = (Get-Content $cpFile -Raw -Encoding UTF8).Trim()
$utf8 = New-Object System.Text.UTF8Encoding($false)
$stderrFile = Join-Path $work 'javac-stderr.txt'

Write-Host "javac   : $javac"
Write-Host "project : $Project"
Write-Host "sources : $($lines.Count)"

$process = Start-Process -FilePath $javac `
    -ArgumentList @('-encoding', 'UTF-8', '-proc:none', '-nowarn', '-d', $classes, '-cp', $classPath, "@$argFile") `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput (Join-Path $work 'javac-stdout.txt') `
    -RedirectStandardError $stderrFile

$stderr = if (Test-Path $stderrFile) { Get-Content $stderrFile -Raw } else { '' }
if ($process.ExitCode -eq 0) {
    Write-Host "OK: 编译通过（$($lines.Count) 个源文件）" -ForegroundColor Green
    exit 0
}

Write-Host "FAILED: javac exit=$($process.ExitCode)" -ForegroundColor Red
if ($stderr) { Write-Host $stderr }
$errors = ([regex]::Matches($stderr, '错误:|error:')).Count
Write-Host "错误条数（粗估）：$errors"
exit $process.ExitCode
