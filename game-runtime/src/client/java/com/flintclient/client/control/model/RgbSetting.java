package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Any opaque colour (a swatch that opens a colour picker), stored in files as {@code "#RRGGBB"}. The value is
 * {@code 0xRRGGBB} (no alpha: transparency, where a module offers it, is a setting of its own). Reading never fails: a
 * missing, malformed or out-of-range value is the default.
 */
public final class RgbSetting extends SettingDescriptor<Integer> {
	public RgbSetting(String id, String label, @Nullable String description, int defaultRgb) {
		super(id, label, description, defaultRgb & 0xFFFFFF);
		if ((defaultRgb & ~0xFFFFFF) != 0) {
			throw new IllegalArgumentException("an RGB default has no alpha: " + id);
		}
	}

	@Override
	public Class<Integer> type() {
		return Integer.class;
	}

	@Override
	public Integer parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			int rgb = parseHex(json.getAsString());
			if (rgb >= 0) {
				return rgb;
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(Integer value) {
		return new JsonPrimitive(hex(value));
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof Integer i && (i & ~0xFFFFFF) == 0;
	}

	@Override
	public Integer clamp(Integer value) {
		return value & 0xFFFFFF;
	}

	/**
	 * The colour in {@code text} ({@code "#RRGGBB"}, {@code "RRGGBB"}, {@code "#RGB"} or {@code "RGB"}, any case, spaces
	 * around ignored), or -1 when it isn't one.
	 */
	public static int parseHex(@Nullable String text) {
		if (text == null) {
			return -1;
		}
		String s = text.strip();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		if (s.length() != 3 && s.length() != 6) {
			return -1;
		}
		int value = 0;
		for (int i = 0; i < s.length(); i++) {
			int digit = Character.digit(s.charAt(i), 16);
			if (digit < 0) {
				return -1;
			}
			value = s.length() == 3 ? value << 8 | digit << 4 | digit : value << 4 | digit;
		}
		return value;
	}

	/** {@code rgb} as {@code "#RRGGBB"}, upper case. */
	public static String hex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}
}
