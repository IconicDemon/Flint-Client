package com.flintclient.client.control.feature;

/**
 * The Camera module's third-person distance as shown: it follows the Distance setting, and when the setting changes (the
 * wheel, a key or the menu) it glides from where it was to the new value over {@link #GLIDE_NS} with the chosen easing, or
 * jumps there when smoothing is off. Times in nanoseconds of a monotonic clock. No Minecraft classes.
 */
public final class CameraDistance {
	static final long GLIDE_NS = 250_000_000L;
	private float target = Float.NaN;
	private float from;
	private long startedAt;

	/** The distance to use at {@code now} for the setting's value {@code wanted}. */
	public float at(float wanted, long now, boolean smooth, ZoomState.Easing easing) {
		if (Float.isNaN(target)) {
			target = wanted;
			from = wanted;
			startedAt = now - GLIDE_NS;
		} else if (wanted != target) {
			from = smooth ? shown(now, easing) : wanted;
			target = wanted;
			startedAt = now;
		}
		return smooth ? shown(now, easing) : target;
	}

	private float shown(long now, ZoomState.Easing easing) {
		float t = Math.clamp((now - startedAt) / (float) GLIDE_NS, 0F, 1F);
		return from + (target - from) * easing.apply(t);
	}

	/** Forget the glide: the next distance is used as it is. */
	public void reset() {
		target = Float.NaN;
	}

	/** The setting's next value after {@code steps} increments (negative: closer), on the setting's grid and in range. */
	public static float stepped(float distance, float increment, int steps, float min, float max) {
		float next = distance + increment * steps;
		// Round to the hundredth: the setting's grid is 0.05, and float sums drift.
		next = Math.round(next * 100F) / 100F;
		return Math.clamp(next, min, max);
	}
}
