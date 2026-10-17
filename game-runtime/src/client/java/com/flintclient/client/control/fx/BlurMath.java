package com.flintclient.client.control.fx;

/**
 * The motion blur's timing rules, free of Minecraft classes so they can be unit tested.
 *
 * <p>Each frame keeps a share of the accumulated image ("retention") and adds the new frame on top. The
 * share follows the real time since the last blended frame, {@code exp(-dt / τ)} with {@code τ = strength ×}
 * {@link #MAX_TIME_CONSTANT_S}, so a trail lasts as long at 240 FPS as at 60 FPS instead of shrinking as the
 * frame rate rises.
 */
public final class BlurMath {
	/** Time constant of the trail at full strength, in seconds (about an eighth of a second). */
	static final double MAX_TIME_CONSTANT_S = 0.12;
	/** The most of the previous image one frame keeps, so every frame moves visibly toward the new one. */
	static final float MAX_RETENTION = 0.96f;
	/** A longer pause between blended frames (Dynamic FPS idling, a stall, the world not drawn) restarts the trail. */
	static final long MAX_GAP_NANOS = 250_000_000L;

	private BlurMath() {
	}

	/** The strength setting kept to 0 (off) – 1 (strongest); NaN counts as off. */
	public static float clampStrength(float strength) {
		if (!(strength > 0)) {
			return 0;
		}
		return Math.min(strength, 1);
	}

	/** Share of the previous image kept in a frame that came {@code frameNanos} after the last one; 0 means no blur. */
	public static float retention(float strength, long frameNanos) {
		float s = clampStrength(strength);
		if (s == 0) {
			return 0;
		}
		double dt = Math.max(frameNanos, 0) / 1e9;
		double keep = Math.exp(-dt / (s * MAX_TIME_CONSTANT_S));
		return (float) Math.min(keep, MAX_RETENTION);
	}

	/** Whether the trail may carry on across a pause of {@code gapNanos} since the last blended frame. */
	public static boolean continues(long gapNanos) {
		return gapNanos >= 0 && gapNanos <= MAX_GAP_NANOS;
	}
}
