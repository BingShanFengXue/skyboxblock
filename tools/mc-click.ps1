# Minecraft 窗口置顶 + 聚焦 + 点击 —— 三件事在同一个进程里原子完成。
#
# 为什么不能分两步：实测有别的程序（SOharness / DSH 自己的窗口）会在
# 「聚焦成功」和「点击」之间弹出来抢走前台，于是点击落到别人身上。
# 分两次调用 PowerShell 就有这个空窗期，所以必须合成一次。
#
# 做法：
#   1. 把 Minecraft 设为 HWND_TOPMOST —— 这样即使别的窗口弹出来，
#      鼠标事件也一定落在 Minecraft 上（鼠标事件按 Z 序命中最顶层窗口）；
#   2. SetForegroundWindow + GetForegroundWindow 客观验证（键盘事件需要焦点）；
#   3. 依次点击给定坐标；
#   4. 恢复 HWND_NOTOPMOST。
#
# 用法：
#   powershell -File tools/mc-click.ps1 -Points '650,445' -Shot build/out.png
#   powershell -File tools/mc-click.ps1 -Points '450,280;450,715' -Double
param(
    [Parameter(Mandatory = $true)][string]$Points,   # "x,y" 或 "x,y;x2,y2"
    [string]$Shot = '',
    [switch]$Double,                                  # 每个点双击
    [int]$AfterClickMs = 1500,
    [switch]$KeepTopMost                              # 不恢复（连续调用时用）
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

if (-not ('McClick' -as [type])) {
    Add-Type -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int X, int Y, int cx, int cy, uint f);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
[DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, int e);
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, int extra);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
[DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
[DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
'@ -Name 'McClick' -Namespace 'Mc'
}

$proc = Get-Process java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $proc) { throw '未找到 Minecraft 窗口' }
$hwnd = $proc.MainWindowHandle

# ── 1. 置顶（鼠标事件一定命中 Minecraft）──────────────────────────
[Mc.McClick]::ShowWindow($hwnd, 9) | Out-Null
Start-Sleep -Milliseconds 300
[Mc.McClick]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0003) | Out-Null   # TOPMOST, NOSIZE|NOMOVE
Start-Sleep -Milliseconds 300

# ── 2. 聚焦（键盘事件需要焦点）+ 客观验证 ─────────────────────────
[Mc.McClick]::keybd_event(0x12, 0, 0, 0)                    # ALT down（解锁前台锁）
Start-Sleep -Milliseconds 120
$fg = [Mc.McClick]::GetForegroundWindow()
$fgPid = 0
$fgThread = [Mc.McClick]::GetWindowThreadProcessId($fg, [ref]$fgPid)
$myThread = [Mc.McClick]::GetCurrentThreadId()
$attached = $false
if ($fgThread -ne 0 -and $fgThread -ne $myThread) {
    $attached = [Mc.McClick]::AttachThreadInput($myThread, $fgThread, $true)
}
[Mc.McClick]::BringWindowToTop($hwnd) | Out-Null
[Mc.McClick]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 250
if ($attached) { [Mc.McClick]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null }
[Mc.McClick]::keybd_event(0x12, 0, 2, 0)                    # ALT up
Start-Sleep -Milliseconds 400

$focused = ([Mc.McClick]::GetForegroundWindow() -eq $hwnd)
if ($focused) { Write-Host '✅ 已聚焦 Minecraft' }
else          { Write-Host '⚠ 焦点未拿到（鼠标点击仍会命中，因为已置顶）' }

# ── 3. 点击 ───────────────────────────────────────────────────────
foreach ($pt in $Points.Split(';')) {
    $xy = $pt.Split(',')
    $x = [int]$xy[0]; $y = [int]$xy[1]
    [Mc.McClick]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 450
    [Mc.McClick]::mouse_event(0x0002, 0, 0, 0, 0); Start-Sleep -Milliseconds 150
    [Mc.McClick]::mouse_event(0x0004, 0, 0, 0, 0)
    if ($Double) {
        Start-Sleep -Milliseconds 160
        [Mc.McClick]::mouse_event(0x0002, 0, 0, 0, 0); Start-Sleep -Milliseconds 150
        [Mc.McClick]::mouse_event(0x0004, 0, 0, 0, 0)
    }
    Write-Host "  点击 ($x,$y)"
    Start-Sleep -Milliseconds $AfterClickMs
}

# ── 4. 截图 ───────────────────────────────────────────────────────
if ($Shot) {
    Start-Sleep -Milliseconds 700
    $bmp = New-Object System.Drawing.Bitmap 1280, 800
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen(50, 50, 0, 0, $bmp.Size)
    $dir = Split-Path -Parent $Shot
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $bmp.Save((Join-Path (Get-Location) $Shot), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Host "  截图: $Shot"
}

# ── 5. 恢复 ───────────────────────────────────────────────────────
if (-not $KeepTopMost) {
    [Mc.McClick]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x0003) | Out-Null   # NOTOPMOST
}
