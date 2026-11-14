package com.flintclient.client.perf;

import com.flintclient.client.FlintLog;

/**
 * "Smooth screen sharing": the game's GPU work goes behind a screen capture's when both want the GPU at once. A game that
 * keeps the GPU busy (uncapped, shaders, a high resolution) otherwise makes Discord's or OBS's capture and encoder wait their
 * turn behind its frames, and the stream skips and repeats frames while the game itself looks smooth. Windows schedules the
 * GPU by process priority class: the game at "below normal" lets that small, regular work go first; with nothing else on the
 * GPU the game runs exactly as before. The callers ask for it only while the game is in a window: with a monitor-covering
 * game the measurements went the other way. Windows only (D3DKMTSetProcessSchedulingPriorityClass on this process, which
 * needs no rights); elsewhere, and where Windows refuses (then once, and not asked again), nothing changes.
 */
public final class GpuPriority {
	/** D3DKMT_SCHEDULINGPRIORITYCLASS. */
	static final int BELOW_NORMAL = 1;
	static final int NORMAL = 2;
	/** The class Flint last set, or -1 while it has set none (the game's normal one). */
	private static int applied = -1;
	private static boolean refused;

	private GpuPriority() {
	}

	/** Puts the game's GPU work behind other programs' ({@code yield}) or back to normal; true when that is in effect. */
	public static boolean apply(boolean yield) {
		int wanted = yield ? BELOW_NORMAL : NORMAL;
		if (applied == wanted || (applied == -1 && !yield)) {
			// Already so (never changed counts as normal).
			return true;
		}
		if (!WinNative.WINDOWS || refused) {
			return false;
		}
		if (!WinNative.gpuPriority(wanted)) {
			refused = true;
			FlintLog.LOGGER.warn("Flint: Windows didn't take the GPU priority; Smooth screen sharing has no effect");
			return false;
		}
		applied = wanted;
		FlintLog.LOGGER.info("Flint: GPU priority {} (Smooth screen sharing)", yield ? "below normal" : "normal");
		return true;
	}

	/** The class now (1 below normal, 2 normal), or -1 when it can't be read. */
	public static int current() {
		return WinNative.gpuPriority();
	}
}
