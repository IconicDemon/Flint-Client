package com.flintclient.client.control.feature;

/**
 * Zoom: how much closer the view is, eased in and out. {@link #factor} is 1 when not zoomed and the chosen level (2× to
 * 30×) when fully zoomed; the field of view is divided by it, so your FOV setting itself never changes. Scrolling while
 * zoomed multiplies the level by 1.25 per notch (kept within 1.5× and 50×) until the zoom ends. Pure Java.
 */
public final class ZoomState {
	public static final float MIN = 1.5F;
	public static final float MAX = 50;
	private static final float STEP = 1.25F;

	private boolean active;
	private float level = 4;
	private float scrolled = 1;
	private float from = 1;
	private float to = 1;
	private long changedAt = Long.MIN_VALUE;

	/** Starts or ends zooming at {@code nowNanos}; {@code chosen} is the module's level. */
	public void set(boolean on, float chosen, long nowNanos) {
		if (on == active) {
			if (on) {
				level = chosen;
			}
			return;
		}
		from = currentRaw(nowNanos);
		active = on;
		level = chosen;
		if (!on) {
			scrolled = 1;
		}
		to = on ? target() : 1;
		changedAt = nowNanos;
	}

	public boolean active() {
		return active;
	}

	/** One scroll notch while zoomed: up (positive) zooms further. Returns whether it applied. */
	public boolean scroll(int notches, long nowNanos) {
		if (!active || notches == 0) {
			return false;
		}
		float next = scrolled * (float) Math.pow(STEP, notches);
		float total = Math.clamp(level * next, MIN, MAX);
		scrolled = total / level;
		from = currentRaw(nowNanos);
		to = target();
		changedAt = nowNanos;
		return true;
	}

	private float target() {
		return Math.clamp(level * scrolled, MIN, MAX);
	}

	private float currentRaw(long nowNanos) {
		return factor(nowNanos, 0, Easing.LINEAR);
	}

	/**
	 * The zoom factor now: 1 when off, the target when on, eased over {@code durationNanos} (0: no easing) with
	 * {@code easing} between the two.
	 */
	public float factor(long nowNanos, long durationNanos, Easing easing) {
		if (durationNanos <= 0 || changedAt == Long.MIN_VALUE) {
			return to;
		}
		float t = Math.clamp((nowNanos - changedAt) / (float) durationNanos, 0, 1);
		// Ease the log of the factor, so zooming in and out feel equally fast.
		double a = Math.log(from);
		double b = Math.log(to);
		return (float) Math.exp(a + (b - a) * easing.apply(t));
	}

	/** The easing curves the Zoom settings offer, in their order. */
	public enum Easing {
		OUT_EXPONENTIAL,
		IN_OUT_QUADRATIC,
		IN_OUT_CIRCULAR,
		OUT_QUINTIC,
		LINEAR;

		public static Easing of(int index) {
			Easing[] all = values();
			return index >= 0 && index < all.length - 1 ? all[index] : OUT_QUINTIC;
		}

		public float apply(float t) {
			return switch (this) {
				case OUT_EXPONENTIAL -> t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
				case IN_OUT_QUADRATIC -> t < 0.5F ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
				case IN_OUT_CIRCULAR -> t < 0.5F ? (1 - (float) Math.sqrt(1 - Math.pow(2 * t, 2))) / 2
					: ((float) Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2;
				case OUT_QUINTIC -> 1 - (float) Math.pow(1 - t, 5);
				case LINEAR -> t;
			};
		}
	}
}
