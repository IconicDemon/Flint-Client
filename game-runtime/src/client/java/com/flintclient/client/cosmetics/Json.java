package com.flintclient.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/** Reading fields that may be missing or of another type, as null (or false). */
final class Json {
	private Json() {
	}

	static @Nullable JsonObject object(@Nullable JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement v = parent.get(key);
		return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
	}

	static @Nullable String string(@Nullable JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement v = parent.get(key);
		return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
	}

	static boolean bool(@Nullable JsonObject parent, String key) {
		if (parent == null) {
			return false;
		}
		JsonElement v = parent.get(key);
		return v instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
	}

	/** A whole number within {@code min..max}, or null. */
	static @Nullable Long integer(@Nullable JsonObject parent, String key, long min, long max) {
		if (parent == null) {
			return null;
		}
		JsonElement v = parent.get(key);
		if (!(v instanceof JsonPrimitive p) || !p.isNumber()) {
			return null;
		}
		double d = p.getAsDouble();
		if (!Double.isFinite(d) || d != Math.floor(d) || d < min || d > max) {
			return null;
		}
		return (long) d;
	}
}
