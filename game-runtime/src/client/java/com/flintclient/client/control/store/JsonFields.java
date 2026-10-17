package com.flintclient.client.control.store;

import com.flintclient.client.control.model.SettingDescriptor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Small helpers for reading fields that may be missing or wrong, and for updating objects in place.
 *
 * <p>Writing follows one rule: a known field is rewritten only when its value differs from what the file's own
 * value reads as. So a value this version can't use (an option added by a newer Flint, say) stays in the file
 * until the player actually changes that setting, instead of being replaced by the default on the next save.
 * (The other side of this: an override this version reads as "none" can't be cleared by this version either,
 * since there is nothing for it to show or reset.)
 */
final class JsonFields {
	private JsonFields() {
	}

	/** The object at {@code key}, or null when it is missing or not an object. */
	static @Nullable JsonObject object(@Nullable JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement value = parent.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	/** The object at {@code key}, made (or put in place of a value of another type) when needed. */
	static JsonObject child(JsonObject parent, String key) {
		JsonObject existing = object(parent, key);
		if (existing != null) {
			return existing;
		}
		JsonObject created = new JsonObject();
		parent.add(key, created);
		return created;
	}

	/** The text at {@code key}, or null when it is missing or not a string. */
	static @Nullable String string(@Nullable JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement value = parent.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	/** The finite number at {@code key}, or {@code fallback}. */
	static float number(@Nullable JsonObject parent, String key, float fallback) {
		if (parent == null) {
			return fallback;
		}
		JsonElement value = parent.get(key);
		if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			float number = value.getAsFloat();
			if (Float.isFinite(number)) {
				return number;
			}
		}
		return fallback;
	}

	/** The strings in the array at {@code key} (other entries skipped), or an empty list. */
	static List<String> strings(@Nullable JsonObject parent, String key) {
		List<String> result = new ArrayList<>();
		if (parent == null) {
			return result;
		}
		JsonElement value = parent.get(key);
		if (value != null && value.isJsonArray()) {
			for (JsonElement element : value.getAsJsonArray()) {
				if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
					result.add(element.getAsString());
				}
			}
		}
		return result;
	}

	/** Writes {@code value} for {@code setting} at {@code key}, unless the file's value there already reads the same. */
	static <T> void put(JsonObject parent, String key, SettingDescriptor<T> setting, T value) {
		JsonElement existing = parent.get(key);
		if (existing == null || !setting.parse(existing).equals(value)) {
			parent.add(key, setting.toJson(value));
		}
	}

	/**
	 * Writes {@code json} at {@code key} (removing the key when it is null), unless the file already has a value
	 * there that reads as {@code current} ({@code onFile} being what it reads as).
	 */
	static void put(JsonObject parent, String key, @Nullable Object onFile, @Nullable Object current, @Nullable JsonElement json) {
		if (parent.has(key) && Objects.equals(onFile, current)) {
			return;
		}
		if (json == null) {
			parent.remove(key);
		} else {
			parent.add(key, json);
		}
	}

	/** True or false at {@code key}, or null when it is missing or not a boolean. */
	static @Nullable Boolean bool(@Nullable JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement value = parent.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
	}

	static JsonArray array(List<String> values) {
		JsonArray array = new JsonArray();
		for (String value : values) {
			array.add(new JsonPrimitive(value));
		}
		return array;
	}
}
