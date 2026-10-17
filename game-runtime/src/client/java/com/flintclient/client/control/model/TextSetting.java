package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/**
 * A short line of text (a label a module shows, a local nickname). Control characters are removed and the text is cut
 * to {@link #maxLength()}, so a hand-edited file can't put anything odd on screen.
 */
public final class TextSetting extends SettingDescriptor<String> {
	private final int maxLength;

	public TextSetting(String id, String label, @Nullable String description, String defaultValue, int maxLength) {
		super(id, label, description, defaultValue);
		if (maxLength < 1 || defaultValue.length() > maxLength) {
			throw new IllegalArgumentException("bad length for " + id);
		}
		this.maxLength = maxLength;
	}

	public int maxLength() {
		return maxLength;
	}

	@Override
	public Class<String> type() {
		return String.class;
	}

	@Override
	public String parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			return clamp(json.getAsString());
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(String value) {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof String s && s.equals(clean(s, maxLength));
	}

	@Override
	public String clamp(String value) {
		return clean(value, maxLength);
	}

	/** Without control characters and Minecraft formatting codes (the section sign and the letter after it), cut to {@code max}. */
	static String clean(String value, int max) {
		StringBuilder out = new StringBuilder(Math.min(value.length(), max));
		for (int i = 0; i < value.length() && out.length() < max; i++) {
			char ch = value.charAt(i);
			if (ch == '\u00A7') {
				i++;
			} else if (!Character.isISOControl(ch)) {
				out.append(ch);
			}
		}
		return out.toString();
	}
}
