package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/** A whole-number setting (a slider) that stays within {@code min..max} and on multiples of {@code step} from {@code min}. */
public final class IntSetting extends SettingDescriptor<Integer> {
	private final int min;
	private final int max;
	private final int step;
	private final String unit;
	/** Values shown by a name instead of a number ("Auto", "Unlimited"); set while the descriptor is made. */
	private java.util.Map<Integer, String> names = java.util.Map.of();

	public IntSetting(String id, String label, @Nullable String description, int defaultValue, int min, int max, int step, String unit) {
		super(id, label, description, defaultValue);
		if (min > max || step <= 0 || defaultValue < min || defaultValue > max || (defaultValue - min) % step != 0) {
			throw new IllegalArgumentException("bad range for " + id);
		}
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public int step() {
		return step;
	}

	/**
	 * Shows {@code value} as {@code name} ("Auto" for 0) instead of the number and unit. Only while the descriptor is made:
	 * {@code new IntSetting(...).named(0, "Auto")}.
	 */
	public IntSetting named(int value, String name) {
		java.util.Map<Integer, String> next = new java.util.HashMap<>(names);
		next.put(value, name);
		names = java.util.Map.copyOf(next);
		return this;
	}

	/** Whether some values are shown by a name. */
	public boolean hasNames() {
		return !names.isEmpty();
	}

	/** {@code value} as a slider shows it: its name, else the number and the unit ("12 chunks"). */
	public String format(int value) {
		String name = names.get(value);
		return name != null ? name : value + unit;
	}

	/** Shown after the value ({@code "%"}, {@code "px"}), or empty. */
	public String unit() {
		return unit;
	}

	@Override
	public Class<Integer> type() {
		return Integer.class;
	}

	@Override
	public Integer parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
			double value = json.getAsDouble();
			if (Double.isFinite(value)) {
				return clampInt(value);
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(Integer value) {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof Integer i && i >= min && i <= max && (i - min) % step == 0;
	}

	@Override
	public Integer clamp(Integer value) {
		return clampInt(value);
	}

	/** Snaps to the nearest step, then clamps (the last step may stop short of {@code max}). */
	public int clampInt(double value) {
		long steps = Math.round((value - min) / step);
		long maxSteps = (max - min) / step;
		return (int) (min + Math.clamp(steps, 0, maxSteps) * step);
	}
}
