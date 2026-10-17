package com.flintclient.client.ui.kit;

/**
 * A slider's value rules, kept pure for tests: values stay within [min, max] and on the step grid
 * counted from {@code min} (the top of the range is always reachable, even when the range isn't a whole
 * number of steps).
 */
public final class SliderModel {
	private final float min;
	private final float max;
	private final float step;

	public SliderModel(float min, float max, float step) {
		if (!(max >= min)) {
			throw new IllegalArgumentException("Slider range " + min + ".." + max);
		}
		this.min = min;
		this.max = max;
		this.step = Math.max(0.0F, step);
	}

	public float min() {
		return min;
	}

	public float max() {
		return max;
	}

	public float step() {
		return step;
	}

	/** {@code value} clamped and moved to the nearest step (or to {@code max}, if that is nearer). */
	public float snap(float value) {
		if (Float.isNaN(value)) {
			return min;
		}
		float v = Math.clamp(value, min, max);
		if (step <= 0.0F) {
			return v;
		}
		double stop = min + Math.round((double) (v - min) / step) * (double) step;
		if (stop > max + 1.0E-6) {
			// The nearest grid stop is past the end: the last one inside it.
			stop -= step;
		}
		// Rounding keeps 0.1-style steps from drifting (0.30000001).
		float snapped = (float) (Math.round(stop * 1.0E6) / 1.0E6);
		// The end of the range is a valid stop even when it is off the grid.
		if (Math.abs(max - v) < Math.abs(v - snapped)) {
			return max;
		}
		return Math.clamp(snapped, min, max);
	}

	/** Where {@code value} sits in the range, 0..1 (0 for an empty range). */
	public float fraction(float value) {
		return max > min ? Math.clamp((value - min) / (max - min), 0.0F, 1.0F) : 0.0F;
	}

	/** The snapped value at a position along the track (0..1). */
	public float valueAt(float fraction) {
		return snap(min + Math.clamp(fraction, 0.0F, 1.0F) * (max - min));
	}

	/**
	 * {@code value} moved by {@code steps} grid stops (keyboard: ±1, ±10 with Shift), snapped. Counted on
	 * the grid, so a step down from an off-grid maximum lands on the last stop below it, not one further.
	 */
	public float stepped(float value, int steps) {
		float from = snap(value);
		if (step <= 0.0F) {
			return snap(from + steps * (max - min) / 100.0F);
		}
		// The stop index of the value; the maximum may lie between two stops.
		double index = (from - (double) min) / step;
		double stop = steps > 0 ? Math.floor(index + 1.0E-6) + steps : Math.ceil(index - 1.0E-6) + steps;
		return snap((float) (min + stop * step));
	}
}
