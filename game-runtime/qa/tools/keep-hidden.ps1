# Dev only (Windows): keeps benchmark windows out of sight while the owner's screen is in use (or they sleep). It listens to
# Windows' window events (shown, brought to front, moved), so it acts within milliseconds instead of polling:
# - a Minecraft window (a java/javaw window whose title starts with "Minecraft") is moved past the right edge of every
#   screen, where it keeps rendering (it isn't minimised: Minecraft can't draw into a minimised window);
# - a test launcher (flint-0.5.5-perf, flint-0.6.0-perf, any flint-*-perf process) is minimised without activating it;
# - the window that was in front before is given the front back.
# The owner's installed Flint (flint-app.exe) is never touched. Runs until killed or for -Hours.
# usage: powershell -NoProfile -ExecutionPolicy Bypass -File keep-hidden.ps1 [-Hours 8]
param([double]$Hours = 8)

Add-Type -ReferencedAssemblies System.Windows.Forms, System.Management @"
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

public static class KeepHidden {
	delegate void WinEventProc(IntPtr hook, uint ev, IntPtr hwnd, int idObject, int idChild, uint thread, uint time);
	[DllImport("user32.dll")] static extern IntPtr SetWinEventHook(uint min, uint max, IntPtr mod, WinEventProc proc, uint pid, uint tid, uint flags);
	[DllImport("user32.dll")] static extern bool GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
	[DllImport("user32.dll")] static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
	[DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
	[DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hwnd);
	[DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hwnd, int cmd);
	[DllImport("user32.dll")] static extern bool IsIconic(IntPtr hwnd);
	[DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out RECT r);
	[DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
	[DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
	[DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
	[StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }

	static WinEventProc keep;
	static IntPtr lastOther = IntPtr.Zero;

	static string ProcessName(IntPtr hwnd) {
		uint pid;
		GetWindowThreadProcessId(hwnd, out pid);
		try { return Process.GetProcessById((int)pid).ProcessName.ToLowerInvariant(); } catch { return ""; }
	}

	static readonly System.Collections.Generic.Dictionary<uint, bool> bench = new System.Collections.Generic.Dictionary<uint, bool>();

	/** Whether the window's Java was started for a benchmark: its command line names FlintQA\games or FlintQA\launcher-bench. */
	static bool IsBench(IntPtr hwnd) {
		uint pid;
		GetWindowThreadProcessId(hwnd, out pid);
		bool known;
		if (bench.TryGetValue(pid, out known)) return known;
		bool found = false;
		try {
			using (var search = new System.Management.ManagementObjectSearcher("SELECT CommandLine FROM Win32_Process WHERE ProcessId=" + pid)) {
				foreach (System.Management.ManagementObject p in search.Get()) {
					string line = ((p["CommandLine"] as string) ?? "").Replace('/', '\\').ToLowerInvariant();
					found = line.Contains("\\flintqa\\games\\") || line.Contains("\\flintqa\\launcher-bench");
				}
			}
		} catch { }
		bench[pid] = found;
		return found;
	}

	static void Handle(IntPtr hook, uint ev, IntPtr hwnd, int idObject, int idChild, uint thread, uint time) {
		if (idObject != 0 || hwnd == IntPtr.Zero) return;
		IntPtr top = GetAncestor(hwnd, 2);
		if (top != hwnd) return;
		string name = ProcessName(hwnd);
		bool launcher = name.StartsWith("flint-") && name.EndsWith("-perf");
		bool game = false;
		if (name == "java" || name == "javaw") {
			StringBuilder title = new StringBuilder(256);
			GetWindowText(hwnd, title, 256);
			// The game, or Fabric Loader's error window when a run's mods don't fit together; only a benchmark's own
			// (its command line names a benchmark folder), so a game the owner starts is never moved.
			game = (title.ToString().StartsWith("Minecraft") || title.ToString().StartsWith("Fabric Loader")) && IsBench(hwnd);
		}
		if (!launcher && !game) {
			if (ev == 0x0003) lastOther = hwnd;
			return;
		}
		if (launcher) {
			if (!IsIconic(hwnd)) ShowWindow(hwnd, 7);
		} else {
			RECT r;
			GetWindowRect(hwnd, out r);
			int right = GetSystemMetrics(76) + GetSystemMetrics(78);
			if (r.Left < right + 50) SetWindowPos(hwnd, new IntPtr(1), right + 100, 0, 0, 0, 0x0001 | 0x0010 | 0x0004);
		}
		if (GetForegroundWindow() == hwnd && lastOther != IntPtr.Zero) SetForegroundWindow(lastOther);
	}

	public static void Run(double hours) {
		keep = Handle;
		// EVENT_SYSTEM_FOREGROUND (3), EVENT_OBJECT_SHOW (0x8002) .. EVENT_OBJECT_LOCATIONCHANGE (0x800B); out of context, skip own.
		SetWinEventHook(0x0003, 0x0003, IntPtr.Zero, keep, 0, 0, 0x0002);
		SetWinEventHook(0x8002, 0x800B, IntPtr.Zero, keep, 0, 0, 0x0002);
		DateTime until = DateTime.Now.AddHours(hours);
		System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
		timer.Interval = 60000;
		timer.Tick += (s, e) => { if (DateTime.Now > until) System.Windows.Forms.Application.Exit(); };
		timer.Start();
		System.Windows.Forms.Application.Run();
	}
}
"@

[KeepHidden]::Run($Hours)
