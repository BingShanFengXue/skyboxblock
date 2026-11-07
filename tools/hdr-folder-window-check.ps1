param([Parameter(Mandatory=$true)][string]$TargetPath,[Parameter(Mandatory=$true)][string]$Evidence,[int]$TimeoutSeconds=240)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class HdrWindow {
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int w,int hgt,uint flags);
}
'@
$expected=[IO.Path]::GetFullPath($TargetPath).TrimEnd('\')
$shell=New-Object -ComObject Shell.Application
$initial=@{}
foreach($window in @($shell.Windows())) {try {$initial[[string]$window.HWND]=$true} catch {}}
[IO.File]::WriteAllText(($Evidence+'.ready'),'ready',[Text.Encoding]::UTF8)
$until=(Get-Date).AddSeconds($TimeoutSeconds)
$result=@{ok=$false;expected=$expected;initialWindowCount=$initial.Count;meaning='Real Explorer LocationURL after actual client command; exact path comparison';matches=@()}
while((Get-Date) -lt $until) {
 foreach($window in @($shell.Windows())) {
  try {
   $hwnd=[long]$window.HWND
   if($initial.ContainsKey([string]$hwnd)) {continue}
   $url=[string]$window.LocationURL
   if(-not $url.StartsWith('file:',[StringComparison]::OrdinalIgnoreCase)) {continue}
   $actual=[IO.Path]::GetFullPath(([uri]$url).LocalPath).TrimEnd('\')
   if([string]::Equals($actual,$expected,[StringComparison]::OrdinalIgnoreCase)) {
    [void][HdrWindow]::ShowWindow([IntPtr]$hwnd,0)
    [void][HdrWindow]::SetWindowPos([IntPtr]$hwnd,[IntPtr]::Zero,0,0,0,0,0x97)
    $result.ok=$true
    $result.matches+=@{hwnd=$hwnd;locationURL=$url;actual=$actual;hidden=$true;at=(Get-Date).ToString('o')}
    $window.Quit()
   }
  } catch {}
 }
 if($result.ok) {break}
 Start-Sleep -Milliseconds 100
}
$result | ConvertTo-Json -Depth 6 | Out-File -FilePath $Evidence -Encoding utf8
if(-not $result.ok) {exit 1}
