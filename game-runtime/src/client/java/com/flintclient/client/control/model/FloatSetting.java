package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/**
 * A decimal setting (a slider) that stays within {@code min..max} and on multiples of {@code step} from
 * {@code min}. Steps are counted in whole numbers from the decimals the float literals stand for, and multiplied
 * out in double precision, so 12 steps of 0.05 is exactly {@code 0.6F}, not 0.6000001, and 2,000 steps of 0.05
 * reach 100.
 */
public final class FloatSetting extends SettingDescriptor<Float> {
	private final float min;
	private final float max;
	private final float step;
	private final String unit;
	private final double exactMin;
	private final double exactStep;
	private final long maxSteps;

	public FloatSetting(String id, String label, @Nullable String description, float defaultValue, float min, float max, float step,
		String unit) {
		super(id, label, description, defaultValue);
		if (!(min <= max) || !(step > 0) || !(defaultValue >= min && defaultValue <= max)) {
			throw new IllegalArgumentException("bad range for " + id);
		}
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit;
		this.exactMin = decimal(min);
		this.exactStep = decimal(step);
		this.maxSteps = (long) Math.floor((decimal(max) - exactMin) / exactStep + 1.0E-9);
		if (clampFloat(defaultValue) != defaultValue) {
			throw new IllegalArgumentException("default off the step for " + id);
		}
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

	public String unit() {
		return unit;
	}

	@Override
	public Class<Float> type() {
		return Float.class;
	}

	@Override
	public Float parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
			double value = json.getAsDouble();
			if (Double.isFinite(value)) {
				return clampFloat(value);
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(Float value) {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof Float f && Float.isFinite(f) && clampFloat(f) == f;
	}

	@Override
	public Float clamp(Float value) {
		return Float.isFinite(value) ? clampFloat(value) : defaultValue();
	}

	/** Snaps to the nearest step, then clamps; NaN means the default. */
	public float clampFloat(double value) {
		if (Double.isNaN(value)) {
			return defaultValue();
		}
		long steps = Math.clamp(Math.round((value - exactMin) / exactStep), 0, maxSteps);
		return (float) (exactMin + steps * exactStep);
	}

	/** The float literal's intended decimal ({@code 0.05F} is 0.0500000007 as a double; this gives 0.05). */
	private static double decimal(float value) {
		return Double.parseDouble(Float.toString(value));
	}
}
