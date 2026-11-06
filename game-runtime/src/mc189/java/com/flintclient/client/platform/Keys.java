package com.flintclient.client.platform;

import com.flintclient.legacy.shared.KeyNames189;
import java.util.Locale;
import org.lwjgl.input.Keyboard;

/**
 * The keys and mouse buttons Flint's screens name, in Minecraft 1.8.9's numbering (LWJGL 2: Escape 1, left button 0), and the
 * names of keys as players see them. Platform class (see {@link Clock}).
 */
public final class Keys {
	public static final int TAB = Keyboard.KEY_TAB;
	public static final int BACKSPACE = Keyboard.KEY_BACK;
	public static final int DELETE = Keyboard.KEY_DELETE;
	public static final int HOME = Keyboard.KEY_HOME;
	public static final int END = Keyboard.KEY_END;
	public static final int PAGE_UP = Keyboard.KEY_PRIOR;
	public static final int PAGE_DOWN = Keyboard.KEY_NEXT;
	public static final int RETURN = Keyboard.KEY_RETURN;
	public static final int NUMPAD_ENTER = Keyboard.KEY_NUMPADENTER;
	public static final int SPACE = Keyboard.KEY_SPACE;
	public static final int LEFT_SHIFT = Keyboard.KEY_LSHIFT;
	public static final int RIGHT_SHIFT = Keyboard.KEY_RSHIFT;
	public static final int LEFT_ALT = Keyboard.KEY_LMENU;
	public static final int RIGHT_ALT = Keyboard.KEY_RMENU;
	public static final int LEFT_BRACKET = Keyboard.KEY_LBRACKET;
	public static final int RIGHT_BRACKET = Keyboard.KEY_RBRACKET;
	/** LWJGL 2 numbers mouse buttons from 0: left 0, right 1, middle 2. */
	public static final int MOUSE_LEFT = 0;
	public static final int MOUSE_RIGHT = 1;
	public static final int MOUSE_MIDDLE = 2;
	/** The first side button (the rest follow it). */
	public static final int MOUSE_4 = 3;

	private Keys() {
	}

	/** A mouse button's stored name ({@code key.mouse.left}), as Flint's key settings keep it on every line. */
	public static String mouseName(int button) {
		// 1.8.9's key bindings keep a mouse button as its number less 100.
		return KeyNames189.name(button - 100);
	}

	/**
	 * What a stored key name ({@code key.keyboard.right.shift}) shows as ("Right Shift"), as newer Minecraft's Controls name it:
	 * a letter or digit as itself, others word by word; the name itself if it isn't one.
	 */
	public static String displayName(String keyName) {
		String rest;
		boolean mouse = keyName.startsWith("key.mouse.");
		if (mouse) {
			rest = keyName.substring("key.mouse.".length());
		} else if (keyName.startsWith("key.keyboard.")) {
			rest = keyName.substring("key.keyboard.".length());
		} else {
			return keyName;
		}
		if (mouse) {
			return switch (rest) {
				case "left" -> "Left Button";
				case "right" -> "Right Button";
				case "middle" -> "Middle Button";
				default -> "Button " + rest;
			};
		}
		if (rest.length() == 1) {
			return rest.toUpperCase(Locale.ROOT);
		}
		if (rest.startsWith("keypad.")) {
			return "Keypad " + words(rest.substring("keypad.".length()));
		}
		return switch (rest) {
			case "grave.accent" -> "`";
			case "left.bracket" -> "[";
			case "right.bracket" -> "]";
			case "apostrophe" -> "'";
			case "backslash" -> "\\";
			case "comma" -> ",";
			case "equal" -> "=";
			case "minus" -> "-";
			case "period" -> ".";
			case "semicolon" -> ";";
			case "slash" -> "/";
			case "win" -> "Windows";
			default -> rest.matches("f\\d+") ? rest.toUpperCase(Locale.ROOT) : words(rest);
		};
	}

	/** "right.shift" as "Right Shift". */
	private static String words(String dotted) {
		StringBuilder out = new StringBuilder();
		for (String part : dotted.split("\\.")) {
			if (part.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return out.toString();
	}
}
