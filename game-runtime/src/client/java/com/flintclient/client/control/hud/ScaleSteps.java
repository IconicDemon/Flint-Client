package com.flintclient.client.control.hud;

/**
 * The HUD sizes a module can have. Text is rasterised per size, so a fixed handful of steps keeps the number
 * of font sets small and every size crisp.
 */
public final class ScaleSteps {
	private static final float[] STEPS = {0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 2.0F};
	public static final float DEFAULT = 1.0F;

	private ScaleSteps() {
	}

	public static int count() {
		return STEPS.length;
	}

	public static float step(int index) {
		return STEPS[Math.clamp(index, 0, STEPS.length - 1)];
	}

	/** The index of the step nearest {@code scale} (ties go to the smaller step; NaN means the default). */
	public static int index(float scale) {
		if (Float.isNaN(scale)) {
			return index(DEFAULT);
		}
		int best = 0;
		for (int i = 1; i < STEPS.length; i++) {
			if (Math.abs(STEPS[i] - scale) < Math.abs(STEPS[best] - scale)) {
				best = i;
			}
		}
		return best;
	}

	/** The step nearest {@code scale}. */
	public static float snap(float scale) {
		return STEPS[index(scale)];
	}

	/** The next larger step, or the largest. */
	public static float next(float scale) {
		return step(index(scale) + 1);
	}

	/** The next smaller step, or the smallest. */
	public static float previous(float scale) {
		return step(index(scale) - 1);
	}

	public static float min() {
		return STEPS[0];
	}

	public static float max() {
		return STEPS[STEPS.length - 1];
	}
}
