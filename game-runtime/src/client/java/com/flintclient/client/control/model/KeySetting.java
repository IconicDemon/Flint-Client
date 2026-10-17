package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A key (or mouse button) a module listens for, stored by Minecraft's version-neutral key name ({@code
 * "key.keyboard.z"}, {@code "key.mouse.middle"}), the same names options.txt has used since 1.13, so a layout keeps its
 * keys across Minecraft versions. Empty means no key. The version layer turns the name into a key and reads its state.
 */
public final class KeySetting extends SettingDescriptor<String> {
	/** No key. */
	public static final String NONE = "";
	private static final Pattern NAME = Pattern.compile("key\\.(keyboard|mouse)\\.[a-z0-9._]{1,40}");

	public KeySetting(String id, String label, @Nullable String description, String defaultKey) {
		super(id, label, description, defaultKey);
		if (!defaultKey.isEmpty() && !NAME.matcher(defaultKey).matches()) {
			throw new IllegalArgumentException("bad key name for " + id + ": " + defaultKey);
		}
	}

	@Override
	public Class<String> type() {
		return String.class;
	}

	@Override
	public String parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			String value = json.getAsString().strip();
			if (value.isEmpty() || NAME.matcher(value).matches()) {
				return value;
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(String value) {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof String s && (s.isEmpty() || NAME.matcher(s).matches());
	}

	@Override
	public String clamp(String value) {
		return valid(value) ? value : defaultValue();
	}
}
