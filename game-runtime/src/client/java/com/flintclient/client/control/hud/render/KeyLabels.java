package com.flintclient.client.control.hud.render;

import java.util.Locale;
import java.util.Map;

/**
 * Short labels for the keystrokes module's keys, from the names Minecraft gives the bound keys ("W",
 * "Left Shift", "Left Button"). A key is 16 units wide by default, so a label longer than a few letters would
 * be cut off: common English names get the usual abbreviations, keypad keys a "KP" prefix, and anything else
 * is written in capitals without spaces and kept to {@link #MAX_CHARS} characters. Built only when a binding
 * changes. Pure, for tests.
 */
public final class KeyLabels {
	/** What an unbound key shows. */
	public static final String UNBOUND = "—";
	/** The longest label made from a name the table doesn't know. */
	public static final int MAX_CHARS = 5;

	private static final Map<String, String> NAMES = Map.ofEntries(
		Map.entry("left shift", "LSHFT"), Map.entry("right shift", "RSHFT"),
		Map.entry("left control", "LCTRL"), Map.entry("right control", "RCTRL"),
		Map.entry("left alt", "LALT"), Map.entry("right alt", "RALT"),
		Map.entry("left option", "LOPT"), Map.entry("right option", "ROPT"),
		Map.entry("left command", "LCMD"), Map.entry("right command", "RCMD"),
		Map.entry("left win", "LWIN"), Map.entry("right win", "RWIN"),
		Map.entry("left windows", "LWIN"), Map.entry("right windows", "RWIN"),
		Map.entry("left meta", "LMETA"), Map.entry("right meta", "RMETA"),
		Map.entry("space", "SPACE"), Map.entry("tab", "TAB"), Map.entry("caps lock", "CAPS"),
		Map.entry("enter", "ENTER"), Map.entry("backspace", "BKSP"), Map.entry("escape", "ESC"),
		Map.entry("delete", "DEL"), Map.entry("insert", "INS"), Map.entry("home", "HOME"), Map.entry("end", "END"),
		Map.entry("page up", "PGUP"), Map.entry("page down", "PGDN"),
		Map.entry("up arrow", "UP"), Map.entry("down arrow", "DOWN"), Map.entry("left arrow", "LEFT"),
		Map.entry("right arrow", "RIGHT"), Map.entry("num lock", "NUM"), Map.entry("scroll lock", "SCRL"),
		Map.entry("print screen", "PRTSC"), Map.entry("pause", "PAUSE"));

	private KeyLabels() {
	}

	/**
	 * The label for a mouse button numbered as Minecraft numbers them (1 left, 2 middle, 3 right, then 4, 5,
	 * ...): LMB, MMB, RMB, then M4, M5 and so on.
	 */
	public static String mouse(int button) {
		return switch (button) {
			case 1 -> "LMB";
			case 2 -> "MMB";
			case 3 -> "RMB";
			default -> "M" + button;
		};
	}

	/** The label for a keyboard key from the name Minecraft shows for it; {@link #UNBOUND} for none. */
	public static String keyboard(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty()) {
			return UNBOUND;
		}
		if (trimmed.codePointCount(0, trimmed.length()) == 1) {
			return trimmed.toUpperCase(Locale.ROOT);
		}
		String lower = trimmed.toLowerCase(Locale.ROOT);
		String known = NAMES.get(lower);
		if (known != null) {
			return known;
		}
		if (lower.startsWith("keypad ")) {
			return clip("KP" + compact(trimmed.substring("keypad ".length())));
		}
		return clip(compact(trimmed));
	}

	/** Capitals without spaces. */
	private static String compact(String text) {
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(out::appendCodePoint);
		return out.toString().toUpperCase(Locale.ROOT);
	}

	/** At most {@link #MAX_CHARS} characters, never splitting a surrogate pair. */
	private static String clip(String text) {
		if (text.codePointCount(0, text.length()) <= MAX_CHARS) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, MAX_CHARS));
	}
}
