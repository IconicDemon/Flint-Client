package com.flintclient.legacy.shared;

import java.util.HashMap;
import java.util.Map;
import org.lwjgl.input.Keyboard;

/**
 * Minecraft 1.8.9's key codes (LWJGL 2's keyboard codes; mouse buttons as {@code -100 + button}, as 1.8.9's own key bindings
 * store them; 0 for none) and the version-neutral key names Flint's shared settings store ({@code "key.keyboard.c"},
 * {@code "key.mouse.middle"}: options.txt's names since 1.13, see {@code KeySetting}), so a key chosen on 1.8.9 means the same
 * key on every Minecraft line. The {@code Keyboard.KEY_*} values are compile-time constants: nothing here touches LWJGL or the
 * game, so it is unit-tested as it is.
 */
public final class KeyNames189 {
	private static final Map<Integer, String> NAMES = new HashMap<Integer, String>();
	private static final Map<String, Integer> CODES = new HashMap<String, Integer>();

	static {
		String letters = "abcdefghijklmnopqrstuvwxyz";
		int[] letterCodes = {Keyboard.KEY_A, Keyboard.KEY_B, Keyboard.KEY_C, Keyboard.KEY_D, Keyboard.KEY_E, Keyboard.KEY_F, Keyboard.KEY_G,
			Keyboard.KEY_H, Keyboard.KEY_I, Keyboard.KEY_J, Keyboard.KEY_K, Keyboard.KEY_L, Keyboard.KEY_M, Keyboard.KEY_N, Keyboard.KEY_O,
			Keyboard.KEY_P, Keyboard.KEY_Q, Keyboard.KEY_R, Keyboard.KEY_S, Keyboard.KEY_T, Keyboard.KEY_U, Keyboard.KEY_V, Keyboard.KEY_W,
			Keyboard.KEY_X, Keyboard.KEY_Y, Keyboard.KEY_Z};
		for (int i = 0; i < letterCodes.length; i++) {
			key(letterCodes[i], String.valueOf(letters.charAt(i)));
		}
		int[] digits = {Keyboard.KEY_0, Keyboard.KEY_1, Keyboard.KEY_2, Keyboard.KEY_3, Keyboard.KEY_4, Keyboard.KEY_5, Keyboard.KEY_6,
			Keyboard.KEY_7, Keyboard.KEY_8, Keyboard.KEY_9};
		int[] keypad = {Keyboard.KEY_NUMPAD0, Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3, Keyboard.KEY_NUMPAD4,
			Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, Keyboard.KEY_NUMPAD7, Keyboard.KEY_NUMPAD8, Keyboard.KEY_NUMPAD9};
		for (int i = 0; i < 10; i++) {
			key(digits[i], Integer.toString(i));
			key(keypad[i], "keypad." + i);
		}
		int[] functions = {Keyboard.KEY_F1, Keyboard.KEY_F2, Keyboard.KEY_F3, Keyboard.KEY_F4, Keyboard.KEY_F5, Keyboard.KEY_F6, Keyboard.KEY_F7,
			Keyboard.KEY_F8, Keyboard.KEY_F9, Keyboard.KEY_F10, Keyboard.KEY_F11, Keyboard.KEY_F12, Keyboard.KEY_F13, Keyboard.KEY_F14,
			Keyboard.KEY_F15, Keyboard.KEY_F16, Keyboard.KEY_F17, Keyboard.KEY_F18, Keyboard.KEY_F19};
		for (int i = 0; i < functions.length; i++) {
			key(functions[i], "f" + (i + 1));
		}
		key(Keyboard.KEY_ADD, "keypad.add");
		key(Keyboard.KEY_DECIMAL, "keypad.decimal");
		key(Keyboard.KEY_NUMPADENTER, "keypad.enter");
		key(Keyboard.KEY_NUMPADEQUALS, "keypad.equal");
		key(Keyboard.KEY_MULTIPLY, "keypad.multiply");
		key(Keyboard.KEY_DIVIDE, "keypad.divide");
		key(Keyboard.KEY_SUBTRACT, "keypad.subtract");
		key(Keyboard.KEY_UP, "up");
		key(Keyboard.KEY_DOWN, "down");
		key(Keyboard.KEY_LEFT, "left");
		key(Keyboard.KEY_RIGHT, "right");
		key(Keyboard.KEY_APOSTROPHE, "apostrophe");
		key(Keyboard.KEY_BACKSLASH, "backslash");
		key(Keyboard.KEY_COMMA, "comma");
		key(Keyboard.KEY_EQUALS, "equal");
		key(Keyboard.KEY_GRAVE, "grave.accent");
		key(Keyboard.KEY_LBRACKET, "left.bracket");
		key(Keyboard.KEY_RBRACKET, "right.bracket");
		key(Keyboard.KEY_MINUS, "minus");
		key(Keyboard.KEY_PERIOD, "period");
		key(Keyboard.KEY_SEMICOLON, "semicolon");
		key(Keyboard.KEY_SLASH, "slash");
		key(Keyboard.KEY_SPACE, "space");
		key(Keyboard.KEY_TAB, "tab");
		key(Keyboard.KEY_RETURN, "enter");
		key(Keyboard.KEY_ESCAPE, "escape");
		key(Keyboard.KEY_BACK, "backspace");
		key(Keyboard.KEY_DELETE, "delete");
		key(Keyboard.KEY_INSERT, "insert");
		key(Keyboard.KEY_HOME, "home");
		key(Keyboard.KEY_END, "end");
		key(Keyboard.KEY_PRIOR, "page.up");
		key(Keyboard.KEY_NEXT, "page.down");
		key(Keyboard.KEY_CAPITAL, "caps.lock");
		key(Keyboard.KEY_NUMLOCK, "num.lock");
		key(Keyboard.KEY_SCROLL, "scroll.lock");
		key(Keyboard.KEY_PAUSE, "pause");
		key(Keyboard.KEY_SYSRQ, "print.screen");
		key(Keyboard.KEY_APPS, "menu");
		key(Keyboard.KEY_LSHIFT, "left.shift");
		key(Keyboard.KEY_RSHIFT, "right.shift");
		key(Keyboard.KEY_LCONTROL, "left.control");
		key(Keyboard.KEY_RCONTROL, "right.control");
		key(Keyboard.KEY_LMENU, "left.alt");
		key(Keyboard.KEY_RMENU, "right.alt");
		key(Keyboard.KEY_LMETA, "left.win");
		key(Keyboard.KEY_RMETA, "right.win");
		mouse(0, "left");
		mouse(1, "right");
		mouse(2, "middle");
		for (int b = 3; b < 8; b++) {
			mouse(b, Integer.toString(b + 1));
		}
	}

	private KeyNames189() {
	}

	private static void key(int code, String name) {
		put(code, "key.keyboard." + name);
	}

	private static void mouse(int button, String name) {
		put(button - 100, "key.mouse." + name);
	}

	private static void put(int code, String name) {
		if (NAMES.put(code, name) != null || CODES.put(name, code) != null) {
			throw new IllegalStateException("two keys for " + code + " / " + name);
		}
	}

	/** The key name for a 1.8.9 key code; "" for none or a key without a name elsewhere (a key 1.8.9 alone knows). */
	public static String name(int code) {
		String name = NAMES.get(code);
		return name == null ? "" : name;
	}

	/** The 1.8.9 key code for a key name; 0 (none) for "" or a key 1.8.9 can't read. */
	public static int code(String name) {
		Integer code = name == null ? null : CODES.get(name);
		return code == null ? 0 : code;
	}
}
