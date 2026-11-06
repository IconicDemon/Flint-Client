package com.flintclient.client.control.hud;

/**
 * The HUD sizes a module can have: from {@link #min()} to {@link #max()} in steps of five hundredths, typed in the HUD page's
 * inspector or stepped with the wheel (by the Scaling step game option). Text is rasterised per size, so sizes stay on this
 * grid: a fixed set of font sizes, each crisp, made only when first drawn.
 */
public final class ScaleSteps {
	private static final float MIN = 0.25F;
	private static final float MAX = 4.0F;
	private static final float QUANTUM = 0.05F;
	private static final int COUNT = Math.round((MAX - MIN) / QUANTUM) + 1;
	public static final float DEFAULT = 1.0F;
	/** The wheel's step when none is given: a quarter, as the old fixed sizes were apart. */
	public static final float NOTCH = 0.25F;

	private ScaleSteps() {
	}

	public static int count() {
		return COUNT;
	}

	public static float step(int index) {
		return Math.round((MIN + QUANTUM * Math.clamp(index, 0, COUNT - 1)) * 100) / 100.0F;
	}

	/** The index of the size nearest {@code scale} (NaN means the default). */
	public static int index(float scale) {
		if (Float.isNaN(scale)) {
			return index(DEFAULT);
		}
		return Math.clamp(Math.round((scale - MIN) / QUANTUM), 0, COUNT - 1);
	}

	/** The size nearest {@code scale}, in range. */
	public static float snap(float scale) {
		return step(index(scale));
	}

	/** {@code by} larger (a wheel notch), or the largest. */
	public static float next(float scale, float by) {
		return snap(scale + Math.max(QUANTUM, by));
	}

	/** {@code by} smaller, or the smallest. */
	public static float previous(float scale, float by) {
		return snap(scale - Math.max(QUANTUM, by));
	}

	public static float next(float scale) {
		return next(scale, NOTCH);
	}

	public static float previous(float scale) {
		return previous(scale, NOTCH);
	}

	public static float min() {
		return MIN;
	}

	public static float max() {
		return MAX;
	}
}
