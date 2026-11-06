package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A whole number for each of some ids (Better Sounds' per-sound volumes), stored as {@code {"id": value}}. Only ids set
 * away from the default are kept; values are clamped to the range and step; an unusable entry is dropped on its own. The
 * value is an unmodifiable map in id order.
 */
public final class IdValuesSetting extends SettingDescriptor<Map<String, Integer>> {
	/** The most entries kept (far more than anyone sets by hand). */
	public static final int MAX_ENTRIES = 4096;
	private final IdKind kind;
	private final int min;
	private final int max;
	private final int step;
	private final int entryDefault;
	private final String unit;
	/** The value Add gives an id ({@link #addable}); {@code Integer.MIN_VALUE}: every id is listed with − and +. */
	private int addValue = Integer.MIN_VALUE;
	/** Values are hundredths shown as a size ("1.50×") rather than a number and the unit. */
	private boolean times;

	public IdValuesSetting(String id, String label, @Nullable String description, IdKind kind, int entryDefault, int min, int max, int step,
		String unit) {
		this(id, label, description, kind, entryDefault, min, max, step, unit, Map.of());
	}

	/**
	 * With some ids set from the start ({@code defaults}: Sound Physics' sound rates leave rain and thunder out). An id the
	 * player sets back to {@code entryDefault} leaves the map, so it then has {@code entryDefault}, not its starting value.
	 */
	public IdValuesSetting(String id, String label, @Nullable String description, IdKind kind, int entryDefault, int min, int max, int step,
		String unit, Map<String, Integer> defaults) {
		super(id, label, description, sorted(defaults));
		if (min > max || step < 1 || entryDefault < min || entryDefault > max) {
			throw new IllegalArgumentException("bad range for " + id);
		}
		this.kind = kind;
		this.min = min;
		this.max = max;
		this.step = step;
		this.entryDefault = entryDefault;
		this.unit = unit;
		if (!valid(defaultValue())) {
			throw new IllegalArgumentException("bad default entries for " + id);
		}
	}

	private static Map<String, Integer> sorted(Map<String, Integer> defaults) {
		return defaults.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(defaults));
	}

	public IdKind kind() {
		return kind;
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

	/** The value an id has when it isn't in the map. */
	public int entryDefault() {
		return entryDefault;
	}

	public String unit() {
		return unit;
	}

	/**
	 * A list the player adds ids to (UHC Overlay's items): an id not in it shows Add, which sets {@code startValue}; one in it
	 * shows − and + and a remove. Set once, where the setting is declared.
	 */
	public IdValuesSetting addable(int startValue) {
		if (startValue == entryDefault || clampEntry(startValue) != startValue) {
			throw new IllegalArgumentException("bad start value for " + id());
		}
		addValue = startValue;
		return this;
	}

	/** Whether ids are added to this list one by one ({@link #addable}). */
	public boolean addable() {
		return addValue != Integer.MIN_VALUE;
	}

	/** The value Add gives an id. */
	public int addValue() {
		return addValue;
	}

	/** Values are hundredths of a size, shown as "1.50×". Set once, where the setting is declared. */
	public IdValuesSetting times() {
		times = true;
		return this;
	}

	/** Whether values are shown as a size ("1.50×", hundredths) rather than the number and the unit. */
	public boolean shownAsSize() {
		return times;
	}

	/** {@code value} as the menu shows it: "1.50×" for a size, else the number and the unit ("80%"). */
	public String format(int value) {
		return times ? String.format(java.util.Locale.ROOT, "%.2f×", value / 100.0) : value + unit;
	}

	/** {@code id}'s value in {@code values}. */
	public int of(Map<String, Integer> values, String id) {
		Integer value = values.get(id);
		return value != null ? value : entryDefault;
	}

	/** {@code values} with {@code id} set to {@code value} (removed when it's the default). */
	public Map<String, Integer> with(Map<String, Integer> values, String id, int value) {
		TreeMap<String, Integer> next = new TreeMap<>(values);
		int clamped = clampEntry(value);
		if (clamped == entryDefault) {
			next.remove(id);
		} else {
			next.put(id, clamped);
		}
		return clamp(next);
	}

	int clampEntry(int value) {
		int v = Math.max(min, Math.min(max, value));
		return min + Math.round((v - min) / (float) step) * step;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Class<Map<String, Integer>> type() {
		return (Class<Map<String, Integer>>) (Class<?>) Map.class;
	}

	@Override
	public Map<String, Integer> parse(@Nullable JsonElement json) {
		if (json == null || !json.isJsonObject()) {
			return defaultValue();
		}
		TreeMap<String, Integer> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
			JsonElement v = entry.getValue();
			if (IdSetting.validId(entry.getKey()) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
				int value = clampEntry((int) Math.round(v.getAsDouble()));
				if (value != entryDefault) {
					out.put(entry.getKey(), value);
				}
			}
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
		}
		return out.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(out);
	}

	@Override
	public JsonElement toJson(Map<String, Integer> value) {
		JsonObject out = new JsonObject();
		new TreeMap<>(value).forEach((k, v) -> out.add(k, new JsonPrimitive(v)));
		return out;
	}

	@Override
	public boolean valid(@Nullable Object value) {
		if (!(value instanceof Map<?, ?> map) || map.size() > MAX_ENTRIES) {
			return false;
		}
		for (Map.Entry<?, ?> e : map.entrySet()) {
			if (!(e.getKey() instanceof String k) || !IdSetting.validId(k) || !(e.getValue() instanceof Integer v) || v != clampEntry(v)
				|| v == entryDefault) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Map<String, Integer> clamp(Map<String, Integer> value) {
		TreeMap<String, Integer> out = new TreeMap<>();
		for (Map.Entry<String, Integer> e : value.entrySet()) {
			if (e.getKey() != null && IdSetting.validId(e.getKey()) && e.getValue() != null) {
				int v = clampEntry(e.getValue());
				if (v != entryDefault) {
					out.put(e.getKey(), v);
				}
			}
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
		}
		return out.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(out);
	}
}
