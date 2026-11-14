package com.flintclient.client.perf;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.jspecify.annotations.Nullable;

/**
 * The Windows calls Smooth screen sharing needs (GpuPriority), through JNA, which every Minecraft line ships (3.4 on 1.8.9,
 * 5.x on 1.21 and 26.x; only {@code Function.getFunction}, {@code invokeInt}, {@code Pointer} and {@code Memory}, which both
 * have). Nothing here runs off Windows, and a call that can't be made (an older Windows, JNA missing) just reports false: the
 * game then runs as Minecraft does on its own.
 */
final class WinNative {
	static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
	/** GetCurrentProcess(): the pseudo-handle of this process. */
	private static final Pointer SELF = Pointer.createConstant(-1L);

	private static @Nullable Function setGpuPriority;
	private static @Nullable Function getGpuPriority;
	private static boolean loaded;

	private WinNative() {
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!WINDOWS) {
			return;
		}
		try {
			setGpuPriority = Function.getFunction("gdi32", "D3DKMTSetProcessSchedulingPriorityClass");
			getGpuPriority = Function.getFunction("gdi32", "D3DKMTGetProcessSchedulingPriorityClass");
		} catch (Throwable e) {
			setGpuPriority = null;
			getGpuPriority = null;
		}
	}

	/**
	 * This process's GPU scheduling priority class (D3DKMT_SCHEDULINGPRIORITYCLASS: 1 below normal, 2 normal), set; true when
	 * Windows took it. Lowering it needs no rights; the work of other programs (a screen capture, its encoder) then goes first
	 * when both want the GPU at once, and nothing changes when the game is alone on it.
	 */
	static boolean gpuPriority(int priorityClass) {
		load();
		Function set = setGpuPriority;
		if (set == null) {
			return false;
		}
		try {
			return set.invokeInt(new Object[] {SELF, priorityClass}) == 0;
		} catch (Throwable e) {
			setGpuPriority = null;
			return false;
		}
	}

	/** This process's GPU scheduling priority class now, or -1. */
	static int gpuPriority() {
		load();
		Function get = getGpuPriority;
		if (get == null) {
			return -1;
		}
		try {
			Memory out = new Memory(4);
			return get.invokeInt(new Object[] {SELF, out}) == 0 ? out.getInt(0) : -1;
		} catch (Throwable e) {
			return -1;
		}
	}
}
