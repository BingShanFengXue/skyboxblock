# 向运行中的 Minecraft 客户端发送聊天/命令。
# 用剪贴板 + Ctrl+V 而不是逐键模拟 —— 中文输入法（zh_CN）会拦截 keybd_event 的字母键，
# 导致命令被输入成乱码或大小写错误（实测 `SUMMON VEHICLE:QUAD_BIKE` 就是这么来的）。
#
# 用法：
#   powershell -File tools/mc-cmd.ps1 -Commands '/gamemode creative','/tp @s ~ ~ ~ 0 90'
#   powershell -File tools/mc-cmd.ps1 -Commands '/summon vehicle:quad_bike ~ ~2 ~' -Shot build/out.png
param(
    [Parameter(Mandatory = $true)][string[]]$Commands,
    [string]$Shot = '',
    [int]$AfterEachMs = 1200,
    [switch]$NoFocus
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

if (-not ('McNative' -as [type])) {
    Add-Type -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, int extra);
'@ -Name 'McNative' -Namespace 'Mc'
}

$proc = Get-Process java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $proc) { throw '未找到 Minecraft 窗口（标题需以 Minecraft 开头）' }

if (-not $NoFocus) {
    [Mc.McNative]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 800
}

function Send-Tap([byte]$vk) {
    [Mc.McNative]::keybd_event($vk, 0, 0, 0); Start-Sleep -Milliseconds 80
    [Mc.McNative]::keybd_event($vk, 0, 2, 0); Start-Sleep -Milliseconds 220
}

foreach ($cmd in $Commands) {
    Set-Clipboard -Value $cmd
    Send-Tap 0x54                      # T 打开聊天
    Start-Sleep -Milliseconds 900
    [Mc.McNative]::keybd_event(0x11, 0, 0, 0); Start-Sleep -Milliseconds 220   # Ctrl down
    Send-Tap 0x56                      # V
    [Mc.McNative]::keybd_event(0x11, 0, 2, 0); Start-Sleep -Milliseconds 220   # Ctrl up
    Start-Sleep -Milliseconds 700
    Send-Tap 0x0D                      # Enter
    Write-Host "已发送: $cmd"
    Start-Sleep -Milliseconds $AfterEachMs
}

if ($Shot) {
    Start-Sleep -Milliseconds 900
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    # 只截 Minecraft 窗口所在区域（窗口被 SetWindowPos 放在 50,50 尺寸 1280x800）
    $bmp = New-Object System.Drawing.Bitmap 1280, 800
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen(50, 50, 0, 0, $bmp.Size)
    $dir = Split-Path -Parent $Shot
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save((Join-Path (Get-Location) $Shot), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Host "截图: $Shot"
}
