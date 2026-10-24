# 把 Minecraft 窗口强制置前，并用 GetForegroundWindow 客观验证（不是盲点）。
#
# 背景：DSH 自己的窗口会盖在游戏上面，SetForegroundWindow 单调用常常无效
# （Windows 前台锁：只有「当前前台进程」才能改前台窗口）。
# 可靠的组合是 ALT 键技巧 + SetWindowPos(TOPMOST) + BringWindowToTop。
param(
    [switch]$Restore   # 顺便把窗口恢复到 (50,50) 1280x800
)

$ErrorActionPreference = 'Stop'

if (-not ('McFocus' -as [type])) {
    Add-Type -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int X, int Y, int cx, int cy, uint f);
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, int extra);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
[DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
[DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
'@ -Name 'McFocus' -Namespace 'Mc'
}

$proc = Get-Process java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $proc) { throw '未找到 Minecraft 窗口' }
$hwnd = $proc.MainWindowHandle

# ── 1. 先把窗口放到目标位置（可选）────────────────────────────────
if ($Restore) {
    [Mc.McFocus]::ShowWindow($hwnd, 9) | Out-Null           # SW_RESTORE
    Start-Sleep -Milliseconds 400
    [Mc.McFocus]::SetWindowPos($hwnd, [IntPtr]::Zero, 50, 50, 1280, 800, 0x0044) | Out-Null
    Start-Sleep -Milliseconds 400
}

# ── 2. ALT 键技巧：按下 ALT 会让系统允许本进程改前台窗口 ──────────
[Mc.McFocus]::keybd_event(0x12, 0, 0, 0)                    # ALT down
Start-Sleep -Milliseconds 120

# ── 3. 附着到前台线程的输入队列（跨进程 SetForegroundWindow 的经典解法）
$fg = [Mc.McFocus]::GetForegroundWindow()
$fgPid = 0
$fgThread = [Mc.McFocus]::GetWindowThreadProcessId($fg, [ref]$fgPid)
$myThread = [Mc.McFocus]::GetCurrentThreadId()
$attached = $false
if ($fgThread -ne 0 -and $fgThread -ne $myThread) {
    $attached = [Mc.McFocus]::AttachThreadInput($myThread, $fgThread, $true)
}

# ── 4. 三连击：BringWindowToTop → TOPMOST → SetForegroundWindow ──
[Mc.McFocus]::BringWindowToTop($hwnd) | Out-Null
[Mc.McFocus]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0003) | Out-Null   # HWND_TOPMOST, NOSIZE|NOMOVE
[Mc.McFocus]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 300
[Mc.McFocus]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x0003) | Out-Null   # HWND_NOTOPMOST

if ($attached) { [Mc.McFocus]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null }
[Mc.McFocus]::keybd_event(0x12, 0, 2, 0)                    # ALT up
Start-Sleep -Milliseconds 700

# ── 5. 客观验证 ────────────────────────────────────────────────
$now = [Mc.McFocus]::GetForegroundWindow()
if ($now -eq $hwnd) {
    Write-Host "✅ Minecraft 已获得前台焦点 (hwnd=$hwnd)"
    exit 0
} else {
    Write-Host "✗ 焦点仍在别处：前台 hwnd=$now，Minecraft hwnd=$hwnd"
    exit 1
}
