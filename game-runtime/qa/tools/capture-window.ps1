# Dev-only (Windows): finds and places a QA game window for the screen-capture tests (capture-test.mjs), in physical pixels.
# - info:   prints one JSON line: the window handle, its client area on the screen, its monitor, whether it is in front.
# - place:  moves the window onto the given monitor (its top-left corner at +Margin) and keeps it on top there, WITHOUT
#           activating it: the owner keeps the keyboard and the mouse.
# - top:    keeps the window on top of its monitor (no activation), for a fullscreen window that may have been covered.
# - untop:  ends "on top".
# - gpuprio: sets the game process's GPU scheduling priority class (-Priority 0 idle, 1 below normal, 2 normal) and prints it.
# - front:  brings it to the front with focus (only for exclusive fullscreen, which needs it; capture-test.mjs asks for this
#           only when nobody has touched the PC for a while).
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File capture-window.ps1 -ProcessId PID -Action info|place|top|untop|front [-Display \\.\DISPLAY2]
param([Parameter(Mandatory = $true)][int]$ProcessId, [Parameter(Mandatory = $true)][string]$Action, [string]$Display = "\\.\DISPLAY2", [int]$Margin = 40, [int]$Priority = 2)

Add-Type @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class FlintCapWin {
	[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
	[StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
	[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] public struct MONITORINFOEX { public int cbSize; public RECT rcMonitor; public RECT rcWork; public uint dwFlags; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szDevice; }
	public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);
	public delegate bool MonitorEnumProc(IntPtr hMonitor, IntPtr hdc, ref RECT rect, IntPtr data);
	[DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr ctx);
	[DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc proc, IntPtr lParam);
	[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
	[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
	[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
	[DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out RECT r);
	[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT r);
	[DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hwnd, ref POINT p);
	[DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
	[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern bool GetMonitorInfo(IntPtr mon, ref MONITORINFOEX info);
	[DllImport("user32.dll")] public static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr clip, MonitorEnumProc proc, IntPtr data);
	[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
	[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
	[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
	[DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr hwnd, int index);
	[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hwnd);
	[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
	[DllImport("kernel32.dll")] public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
	[DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
	[DllImport("gdi32.dll")] public static extern int D3DKMTSetProcessSchedulingPriorityClass(IntPtr process, int priority);
	[DllImport("gdi32.dll")] public static extern int D3DKMTGetProcessSchedulingPriorityClass(IntPtr process, out int priority);

	public static IntPtr Find(uint pid) {
		IntPtr found = IntPtr.Zero;
		EnumWindows((h, l) => {
			uint p; GetWindowThreadProcessId(h, out p);
			if (p == pid && IsWindowVisible(h)) {
				var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
				if (sb.ToString().StartsWith("Minecraft")) { found = h; return false; }
			}
			return true;
		}, IntPtr.Zero);
		return found;
	}

	public static MONITORINFOEX Info(IntPtr mon) { var i = new MONITORINFOEX(); i.cbSize = Marshal.SizeOf(i); GetMonitorInfo(mon, ref i); return i; }

	public static MONITORINFOEX ByName(string name) {
		MONITORINFOEX result = new MONITORINFOEX();
		EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr m, IntPtr dc, ref RECT r, IntPtr d) => {
			var i = Info(m); if (i.szDevice == name) result = i; return true;
		}, IntPtr.Zero);
		return result;
	}
}
"@

if ($Action -eq "gpuprio") {
	# PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION
	$h = [FlintCapWin]::OpenProcess(0x0200 -bor 0x1000, $false, $ProcessId)
	$set = [FlintCapWin]::D3DKMTSetProcessSchedulingPriorityClass($h, $Priority)
	$now = -1; $get = [FlintCapWin]::D3DKMTGetProcessSchedulingPriorityClass($h, [ref]$now)
	[void][FlintCapWin]::CloseHandle($h)
	Write-Output ('{"set":' + $set + ',"get":' + $get + ',"priority":' + $now + '}')
	exit 0
}
# Physical pixels everywhere (PER_MONITOR_AWARE_V2).
[void][FlintCapWin]::SetThreadDpiAwarenessContext([IntPtr](-4))
$hwnd = [FlintCapWin]::Find([uint32]$ProcessId)
if ($hwnd -eq [IntPtr]::Zero) { Write-Output '{"error":"no window"}'; exit 1 }
$TOPMOST = [IntPtr](-1); $NOTOPMOST = [IntPtr](-2)
# SWP_NOSIZE 1, SWP_NOMOVE 2, SWP_NOACTIVATE 0x10, SWP_SHOWWINDOW 0x40
switch ($Action) {
	"place" {
		$m = [FlintCapWin]::ByName($Display)
		if ($null -eq $m.szDevice) { Write-Output '{"error":"no such display"}'; exit 1 }
		[void][FlintCapWin]::SetWindowPos($hwnd, $TOPMOST, $m.rcMonitor.Left + $Margin, $m.rcMonitor.Top + $Margin, 0, 0, 0x1 -bor 0x10 -bor 0x40)
	}
	"top" { [void][FlintCapWin]::SetWindowPos($hwnd, $TOPMOST, 0, 0, 0, 0, 0x1 -bor 0x2 -bor 0x10 -bor 0x40) }
	# placeback / back: as place / top, but behind every other window (never covers the owner's; window capture still sees it).
	"placeback" {
		$m = [FlintCapWin]::ByName($Display)
		if ($null -eq $m.szDevice) { Write-Output '{"error":"no such display"}'; exit 1 }
		[void][FlintCapWin]::SetWindowPos($hwnd, $NOTOPMOST, 0, 0, 0, 0, 0x1 -bor 0x2 -bor 0x10)
		[void][FlintCapWin]::SetWindowPos($hwnd, [IntPtr]1, $m.rcMonitor.Left + $Margin, $m.rcMonitor.Top + $Margin, 0, 0, 0x1 -bor 0x10)
	}
	"back" {
		[void][FlintCapWin]::SetWindowPos($hwnd, $NOTOPMOST, 0, 0, 0, 0, 0x1 -bor 0x2 -bor 0x10)
		[void][FlintCapWin]::SetWindowPos($hwnd, [IntPtr]1, 0, 0, 0, 0, 0x1 -bor 0x2 -bor 0x10)
	}
	"untop" { [void][FlintCapWin]::SetWindowPos($hwnd, $NOTOPMOST, 0, 0, 0, 0, 0x1 -bor 0x2 -bor 0x10) }
	"front" {
		[FlintCapWin]::keybd_event(0x12, 0x38, 0, [UIntPtr]::Zero)
		[FlintCapWin]::keybd_event(0x12, 0x38, 2, [UIntPtr]::Zero)
		[void][FlintCapWin]::SetForegroundWindow($hwnd)
	}
}
Start-Sleep -Milliseconds 150
$c = New-Object FlintCapWin+RECT; [void][FlintCapWin]::GetClientRect($hwnd, [ref]$c)
$w = New-Object FlintCapWin+RECT; [void][FlintCapWin]::GetWindowRect($hwnd, [ref]$w)
$p = New-Object FlintCapWin+POINT; [void][FlintCapWin]::ClientToScreen($hwnd, [ref]$p)
$mon = [FlintCapWin]::Info([FlintCapWin]::MonitorFromWindow($hwnd, 2))
$o = [ordered]@{
	hwnd = [int64]$hwnd; x = $p.X; y = $p.Y; width = $c.Right - $c.Left; height = $c.Bottom - $c.Top
	windowRect = @($w.Left, $w.Top, $w.Right, $w.Bottom); monitor = $mon.szDevice
	monitorRect = @($mon.rcMonitor.Left, $mon.rcMonitor.Top, $mon.rcMonitor.Right, $mon.rcMonitor.Bottom)
	front = ([FlintCapWin]::GetForegroundWindow() -eq $hwnd); iconic = [FlintCapWin]::IsIconic($hwnd)
	style = ('0x{0:X8}' -f [FlintCapWin]::GetWindowLong($hwnd, -16)); exStyle = ('0x{0:X8}' -f [FlintCapWin]::GetWindowLong($hwnd, -20))
}
Write-Output ($o | ConvertTo-Json -Compress)
