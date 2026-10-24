# 只截图，不点击、不发按键：把 Minecraft 窗口置前并截取 1280x800 区域。
# 用法： powershell -File tools/mc-shot.ps1 -Out build/shot.png [-Restore]
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [switch]$Restore
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

if (-not ('McShot' -as [type])) {
    Add-Type -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int X, int Y, int cx, int cy, uint f);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
'@ -Name 'McShot' -Namespace 'Mc'
}

$proc = Get-Process java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $proc) { throw '未找到 Minecraft 窗口（标题需以 Minecraft 开头）' }

if ($Restore) {
    # HWND_TOP = 0, SWP_NOACTIVATE = 0x10 ; 放到 (50,50) 1280x800
    [Mc.McShot]::SetWindowPos($proc.MainWindowHandle, [IntPtr]::Zero, 50, 50, 1280, 800, 0x10) | Out-Null
    Start-Sleep -Milliseconds 500
}

[Mc.McShot]::BringWindowToTop($proc.MainWindowHandle) | Out-Null
[Mc.McShot]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 700

$bmp = New-Object System.Drawing.Bitmap 1280, 800
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen(50, 50, 0, 0, $bmp.Size)

$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$full = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path (Get-Location) $Out }
$bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Host "截图: $full"
