package com.flintclient.legacy;

/**
 * Dev-only ({@code -Dflint.hudTiming=true}): how long Flint's HUD takes to draw each frame (the shared HUD, Armor and the
 * scoreboard), averaged and logged every 5 seconds, for comparing builds on a machine whose frame rate wanders. Off, it is one
 * constant check per call.
 */
public final class HudTiming {
	public static final boolean ON = Boolean.getBoolean("flint.hudTiming");
	private static long nanos;
	private static long frames;
	private static long lastFrame = -1;
	/** Frames so far (from the frame hook). */
	public static long frame;
	private static long since;
	private static long draws;
	private static long lastBatches;

	/** Before a part is drawn: where the draw call count stands. */
	public static long start() {
		lastBatches = com.flintclient.client.platform.Frame.batches();
		return System.nanoTime();
	}

	private HudTiming() {
	}

	/** {@code start} is {@link System#nanoTime()} before the part drawn. */
	public static void add(long start) {
		long now = System.nanoTime();
		nanos += now - start;
		long b = com.flintclient.client.platform.Frame.batches();
		draws += b - lastBatches;
		lastBatches = b;
		if (frame != lastFrame) {
			lastFrame = frame;
			frames++;
		}
		if (since == 0) {
			since = now;
		} else if (now - since > 5_000_000_000L) {
			Flint.LOG.info(String.format(java.util.Locale.ROOT, "HUD timing (microseconds per frame): total=%.1f frames=%d draws=%.1f",
				frames == 0 ? 0 : nanos / (double) frames / 1000.0, frames, frames == 0 ? 0 : draws / (double) frames));
			draws = 0;
			nanos = 0;
			frames = 0;
			since = now;
		}
	}
}
