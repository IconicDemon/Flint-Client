package com.flintclient.bench;

/**
 * Per-frame and per-client-tick timings in preallocated primitive arrays, so recording never allocates.
 * Everything is recorded from game start; segments are index ranges into these arrays. Written only on
 * the client thread. When an array is full, further samples are dropped and the overflow flag is set.
 */
final class FrameRecorder {
	final long[] frameNs;    // full frame interval, as passed to DebugScreenOverlay.logFrameDuration
	final long[] renderNs;   // Minecraft.getFrameTimeNs(): CPU time from render start to the swapchain blit
	final long[] frameEndNs; // System.nanoTime() when the frame was logged
	final long[] tickNs;     // Minecraft.tick() duration
	final long[] tickEndNs;  // System.nanoTime() at the end of that tick
	int frames;
	int ticks;
	boolean framesOverflowed;
	boolean ticksOverflowed;
	private long tickStartNs;

	FrameRecorder(int frameCapacity, int tickCapacity) {
		frameNs = new long[frameCapacity];
		renderNs = new long[frameCapacity];
		frameEndNs = new long[frameCapacity];
		tickNs = new long[tickCapacity];
		tickEndNs = new long[tickCapacity];
	}

	void onFrame(long intervalNs, long cpuRenderNs, long nowNs) {
		int i = frames;
		if (i == frameNs.length) {
			framesOverflowed = true;
			return;
		}
		frameNs[i] = intervalNs;
		renderNs[i] = cpuRenderNs;
		frameEndNs[i] = nowNs;
		frames = i + 1;
	}

	void tickStarted(long nowNs) {
		tickStartNs = nowNs;
	}

	void tickEnded(long nowNs) {
		int i = ticks;
		if (i == tickNs.length) {
			ticksOverflowed = true;
			return;
		}
		tickNs[i] = nowNs - tickStartNs;
		tickEndNs[i] = nowNs;
		ticks = i + 1;
	}

	/** Bytes held by the arrays, reported so heap numbers can be read with the probe's own share in mind. */
	long footprintBytes() {
		return 8L * (3L * frameNs.length + 2L * tickNs.length);
	}
}
