# 26.2 本地化回归的运行入口（只依赖 JDK + javac，不需要 Gradle，不需要 MC classpath）。
#
# 为什么单独写这个脚本：tools/verify-javac.ps1 把项目根硬编码成 ohwhale-mod，
# 不适用于 skybox-mod-26.2；而本回归是纯文本断言，天然可以脱离 Gradle 跑。
#
# 用法：
#   powershell.exe -ExecutionPolicy Bypass -File tools\run-lang-regression.ps1
#   powershell.exe -ExecutionPolicy Bypass -File tools\run-lang-regression.ps1 -ReferenceRoot <1.21.1 工程根> -JdkHome <JDK 25 目录>
#
# 退出码：0 = PASS，1 = FAIL（失败项逐条打印）。
param(
    # 1.21.1 参照工程根。给了就逐键比对名称；给空串则跳过对照。
    # 1.21.1 参照工程；默认取 mc26.2 的上一层目录，即同一仓库里的 1.21.1 主线。
    [string]$ReferenceRoot = (Split-Path -Parent $PSScriptRoot),
    # JDK 根：显式参数优先，其次环境变量 JAVA_HOME（仓库里不写死本机路径）。
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $JdkHome) {
    throw "未指定 JDK：请传 -JdkHome <JDK 25 目录>，或先设置环境变量 JAVA_HOME。"
}

$proj = Split-Path -Parent $PSScriptRoot
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
foreach ($exe in @($javac, $java)) {
    if (-not (Test-Path $exe)) { throw "找不到 $exe —— 用 -JdkHome 指定一个带 javac 的 JDK" }
}

$testSrc = Join-Path $proj 'src\test\java\dev\yg\skyboxblock\LocalizationRegression.java'
if (-not (Test-Path $testSrc)) { throw "找不到 $testSrc" }

$out = Join-Path $proj 'build\lang-regression'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# 编码必须是 UTF-8：断言失败信息里有中文，默认平台编码会读成乱码。
& $javac -encoding UTF-8 -d $out $testSrc
if ($LASTEXITCODE -ne 0) { throw "javac 编译 LocalizationRegression.java 失败（exit=$LASTEXITCODE）" }

# 反斜杠在命令行/argfile 里会被当转义符，统一换成正斜杠（LESSONS 里的坑 2）。
$projFwd = $proj -replace '\\', '/'
$outFwd = $out -replace '\\', '/'
$args2 = @('-cp', $outFwd, 'dev.yg.skyboxblock.LocalizationRegression', $projFwd)
if ($ReferenceRoot -and (Test-Path $ReferenceRoot)) {
    $args2 += ($ReferenceRoot -replace '\\', '/')
} else {
    Write-Host "（跳过与 1.21.1 的名称对照：ReferenceRoot = '$ReferenceRoot' 不存在）" -ForegroundColor Yellow
}

& $java @args2
$code = $LASTEXITCODE
Write-Host "lang-regression exit=$code"
exit $code
