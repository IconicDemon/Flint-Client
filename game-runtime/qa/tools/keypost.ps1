# Dev-only (Windows): brings a game window to the front and posts a real key press, as a player's key would arrive (scan
# code and virtual key, through user32), then gives the front back to the window that had it and puts the game behind.
# The Windows counterpart of keypost.swift.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File keypost.ps1 <processId> rshift|lshift|<a-z>
param([Parameter(Mandatory = $true)][int]$ProcessId, [Parameter(Mandatory = $true)][ValidatePattern('^(rshift|lshift|[a-z])$')][string]$Key)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class FlintKeyPost {
	[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
	[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
	[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
	[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
	[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@

$owner = [FlintKeyPost]::GetForegroundWindow()
$process = Get-Process -Id $ProcessId -ErrorAction Stop
$window = $process.MainWindowHandle
if ($window -eq [IntPtr]::Zero) { Write-Output "no window"; exit 1 }
# Windows only lets a process take the foreground right after a key event of its own: a lone Alt tap satisfies that.
[FlintKeyPost]::keybd_event(0x12, 0x38, 0, [UIntPtr]::Zero)
[FlintKeyPost]::keybd_event(0x12, 0x38, 2, [UIntPtr]::Zero)
[void][FlintKeyPost]::ShowWindow($window, 9)
[void][FlintKeyPost]::SetForegroundWindow($window)
Start-Sleep -Milliseconds 400
if ([FlintKeyPost]::GetForegroundWindow() -ne $window) { Write-Output "not in front"; exit 1 }
# Set-1 scan codes of the letters (US layout), a to z.
$letters = @(0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C)
if ($Key -eq 'rshift') { $vk = 0xA1; $scan = 0x36 }
elseif ($Key -eq 'lshift') { $vk = 0xA0; $scan = 0x2A }
else { $i = [int][char]$Key - [int][char]'a'; $vk = 0x41 + $i; $scan = $letters[$i] }
[FlintKeyPost]::keybd_event($vk, $scan, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 90
[FlintKeyPost]::keybd_event($vk, $scan, 2, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 150
if ($owner -ne [IntPtr]::Zero -and $owner -ne $window) {
	[void][FlintKeyPost]::SetWindowPos($window, [IntPtr]1, 0, 0, 0, 0, 0x0013)
	[FlintKeyPost]::keybd_event(0x12, 0x38, 0, [UIntPtr]::Zero)
	[FlintKeyPost]::keybd_event(0x12, 0x38, 2, [UIntPtr]::Zero)
	[void][FlintKeyPost]::SetForegroundWindow($owner)
}
Write-Output "posted $Key"
