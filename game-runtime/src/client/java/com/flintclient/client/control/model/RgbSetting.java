package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Any opaque colour (a swatch that opens a colour picker), stored in files as {@code "#RRGGBB"}. The value is
 * {@code 0xRRGGBB} (no alpha: transparency, where a module offers it, is usually an opacity setting of its own). A
 * {@linkplain #clearable() clearable} colour (a background with no opacity beside it) may also be {@link #CLEAR}:
 * nothing is drawn, stored as {@code "transparent"}; whoever draws it checks {@link #isClear} first. Reading never fails: a
 * missing, malformed or out-of-range value is the default.
 */
public final class RgbSetting extends SettingDescriptor<Integer> {
	/** The value of a clearable colour that is see-through: no colour at all (outside 0xRRGGBB, so never a real colour). */
	public static final int CLEAR = 0x1000000;
	private static final String CLEAR_NAME = "transparent";

	private final boolean clearable;

	public RgbSetting(String id, String label, @Nullable String description, int defaultRgb) {
		this(id, label, description, defaultRgb, false);
	}

	/** {@code clearable}: the colour may also be {@link #CLEAR} (Transparent in the picker). */
	public RgbSetting(String id, String label, @Nullable String description, int defaultRgb, boolean clearable) {
		super(id, label, description, defaultRgb & 0xFFFFFF);
		if ((defaultRgb & ~0xFFFFFF) != 0) {
			throw new IllegalArgumentException("an RGB default has no alpha: " + id);
		}
		this.clearable = clearable;
	}

	/** Whether the colour may be {@link #CLEAR}. */
	public boolean clearable() {
		return clearable;
	}

	/** Whether {@code value} is the see-through {@link #CLEAR}. */
	public static boolean isClear(int value) {
		return value == CLEAR;
	}

	@Override
	public Class<Integer> type() {
		return Integer.class;
	}

	@Override
	public Integer parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			if (clearable && json.getAsString().strip().equalsIgnoreCase(CLEAR_NAME)) {
				return CLEAR;
			}
			int rgb = parseHex(json.getAsString());
			if (rgb >= 0) {
				return rgb;
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(Integer value) {
		return new JsonPrimitive(clearable && value == CLEAR ? CLEAR_NAME : hex(value));
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof Integer i && ((i & ~0xFFFFFF) == 0 || clearable && i == CLEAR);
	}

	@Override
	public Integer clamp(Integer value) {
		return clearable && value == CLEAR ? CLEAR : value & 0xFFFFFF;
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
