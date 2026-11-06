# Dev-only (Windows): keeps a QA game window behind the owner's windows. Started with the game, it remembers the window
# that is in front now (the owner's), and for the first $Seconds whenever the game window comes to the front (it does when
# it opens) puts it at the bottom of the stack and gives the front back. The game keeps rendering (it isn't minimised:
# Minecraft can't draw into a minimised window) and, never in front, it doesn't capture the mouse.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File background.ps1 <processId> [seconds]
param([Parameter(Mandatory = $true)][int]$ProcessId, [int]$Seconds = 90)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class FlintBackground {
	[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
	[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
	[DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hWnd);
	[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
	[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
}
"@

$owner = [FlintBackground]::GetForegroundWindow()
$deadline = (Get-Date).AddSeconds($Seconds)
$bottom = [IntPtr]1
# SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE
$flags = 0x0001 -bor 0x0002 -bor 0x0010
while ((Get-Date) -lt $deadline) {
	$process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
	if ($null -eq $process) { exit 0 }
	$window = $process.MainWindowHandle
	if ($window -ne [IntPtr]::Zero -and [FlintBackground]::GetForegroundWindow() -eq $window) {
		[void][FlintBackground]::SetWindowPos($window, $bottom, 0, 0, 0, 0, $flags)
		if ($owner -ne [IntPtr]::Zero -and $owner -ne $window -and [FlintBackground]::IsWindow($owner)) {
			# Windows lets a process set the foreground right after a key event of its own: a lone Alt tap.
			[FlintBackground]::keybd_event(0x12, 0x38, 0, [UIntPtr]::Zero)
			[FlintBackground]::keybd_event(0x12, 0x38, 2, [UIntPtr]::Zero)
			[void][FlintBackground]::SetForegroundWindow($owner)
		}
		Write-Output "moved the game behind"
	}
	Start-Sleep -Milliseconds 250
}
