package com.flintclient.client.control.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Item Customizer's own place for chosen items and kinds of item: an item id ({@code minecraft:diamond_sword}) or a category
 * ({@code category:swords}, {@link #CATEGORY}) to its {@link HeldTransform}, in the order the player added them. Stored as
 * {@code {"id": [x, y, z, rotX, rotY, rotZ, scaleX, scaleY, scaleZ]}}; every number is clamped to the range and step of its
 * kind (the module's own position, rotation and scale settings), and an unusable entry is dropped on its own. The value is
 * an unmodifiable map.
 */
public final class HeldItemsSetting extends SettingDescriptor<Map<String, HeldTransform>> {
	/** The most entries kept (far more than anyone adds by hand). */
	public static final int MAX_ENTRIES = 128;
	/** The namespace of a category's id. */
	public static final String CATEGORY = "category:";
	private final FloatSetting position;
	private final FloatSetting rotation;
	private final FloatSetting scale;

	/** {@code position}, {@code rotation} and {@code scale}: the settings whose range and step each kind of number takes. */
	public HeldItemsSetting(String id, String label, @Nullable String description, FloatSetting position, FloatSetting rotation,
		FloatSetting scale) {
		super(id, label, description, Map.of());
		this.position = position;
		this.rotation = rotation;
		this.scale = scale;
	}

	/** The setting whose range and step component {@code i} takes (0-2 position, 3-5 rotation, 6-8 scale). */
	public FloatSetting component(int i) {
		return i < 3 ? position : i < 6 ? rotation : scale;
	}

	/** Whether {@code id} names an item or a category. */
	public static boolean validKey(@Nullable String id) {
		return id != null && IdSetting.validId(id);
	}

	public static boolean isCategory(String id) {
		return id.startsWith(CATEGORY);
	}

	/** {@code t} with every number on its range and step. */
	public HeldTransform clampTransform(HeldTransform t) {
		float[] v = t.toArray();
		for (int i = 0; i < v.length; i++) {
			v[i] = component(i).clampFloat(Float.isFinite(v[i]) ? v[i] : component(i).defaultValue());
		}
		return HeldTransform.of(v);
	}

	/** {@code entries} with {@code id} set to {@code t} (added last when new, kept in its place otherwise). */
	public Map<String, HeldTransform> with(Map<String, HeldTransform> entries, String id, HeldTransform t) {
		LinkedHashMap<String, HeldTransform> next = new LinkedHashMap<>(entries);
		next.put(id, t);
		return clamp(next);
	}

	/** {@code entries} without {@code id}. */
	public Map<String, HeldTransform> without(Map<String, HeldTransform> entries, String id) {
		LinkedHashMap<String, HeldTransform> next = new LinkedHashMap<>(entries);
		next.remove(id);
		return clamp(next);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Class<Map<String, HeldTransform>> type() {
		return (Class<Map<String, HeldTransform>>) (Class<?>) Map.class;
	}

	@Override
	public Map<String, HeldTransform> parse(@Nullable JsonElement json) {
		if (json == null || !json.isJsonObject()) {
			return defaultValue();
		}
		LinkedHashMap<String, HeldTransform> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
			String key = entry.getKey().strip();
			JsonElement value = entry.getValue();
			if (!validKey(key) || !value.isJsonArray() || value.getAsJsonArray().size() != HeldTransform.SIZE) {
				continue;
			}
			JsonArray array = value.getAsJsonArray();
			float[] v = new float[HeldTransform.SIZE];
			boolean usable = true;
			for (int i = 0; i < v.length && usable; i++) {
				JsonElement n = array.get(i);
				usable = n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber();
				v[i] = usable ? (float) n.getAsDouble() : 0;
			}
			if (usable) {
				out.put(key, clampTransform(HeldTransform.of(v)));
			}
		}
		return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
	}

	@Override
	public JsonElement toJson(Map<String, HeldTransform> value) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, HeldTransform> entry : value.entrySet()) {
			JsonArray array = new JsonArray();
			for (float f : entry.getValue().toArray()) {
				array.add(new JsonPrimitive(f));
			}
			out.add(entry.getKey(), array);
		}
		return out;
	}

	@Override
	public boolean valid(@Nullable Object value) {
		if (!(value instanceof Map<?, ?> map) || map.size() > MAX_ENTRIES) {
			return false;
		}
		for (Map.Entry<?, ?> e : map.entrySet()) {
			if (!(e.getKey() instanceof String k) || !validKey(k) || !(e.getValue() instanceof HeldTransform t) || !t.equals(clampTransform(t))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Map<String, HeldTransform> clamp(Map<String, HeldTransform> value) {
		LinkedHashMap<String, HeldTransform> out = new LinkedHashMap<>();
		for (Map.Entry<String, HeldTransform> e : value.entrySet()) {
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
			if (validKey(e.getKey()) && e.getValue() != null) {
				out.put(e.getKey(), clampTransform(e.getValue()));
			}
		}
		return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
	}
}
