package com.flintclient.client.platform;

import com.flintclient.legacy.shared.KeyNames189;
import org.lwjgl.input.Keyboard;

/**
 * A key press as Flint's screens read it, on Minecraft 1.8.9: LWJGL 2's key code (compare with {@link Keys}) and the
 * modifiers held when it came, with the same questions the 26.x one answers (Escape, arrows, selection, edit shortcuts,
 * the stored key name). Platform class (see {@link Clock}).
 */
public final class KeyInput {
	private static final int SHIFT = 1;
	private static final int CONTROL = 2;
	private static final int ALT = 4;
	private static final int META = 8;
	private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

	private final int key;
	private final int modifiers;

	private KeyInput(int key, int modifiers) {
		this.key = key;
		this.modifiers = modifiers;
	}

	/** {@code key} (an LWJGL 2 code) with the modifiers held now. */
	public static KeyInput of(int key) {
		int m = 0;
		if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
			m |= SHIFT;
		}
		if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
			m |= CONTROL;
		}
		if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
			m |= ALT;
		}
		if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA)) {
			m |= META;
		}
		return new KeyInput(key, m);
	}

	/**
	 * The character a shortcut with this key types ('z' for Ctrl+Z), or 0: LWJGL 2 numbers keys by their place on a US
	 * keyboard, so this is the key's US letter or bracket.
	 */
	public int shortcutKey() {
		String name = Keyboard.getKeyName(key);
		if (name != null && name.length() == 1) {
			return Character.toLowerCase(name.charAt(0));
		}
		return key == Keyboard.KEY_LBRACKET ? '[' : key == Keyboard.KEY_RBRACKET ? ']' : 0;
	}

	/** The key (compare with {@link Keys}). */
	public int key() {
		return key;
	}

	/** The key's stored name ({@code key.keyboard.a}), as Flint's key settings keep it on every line. */
	public String keyName() {
		return KeyNames189.name(key);
	}

	/** Enter, Space or the keypad's Enter: activates a focused control. */
	public boolean isSelection() {
		return key == Keyboard.KEY_RETURN || key == Keyboard.KEY_SPACE || key == Keyboard.KEY_NUMPADENTER;
	}

	/** Enter or the keypad's Enter. */
	public boolean isConfirmation() {
		return key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER;
	}

	public boolean isEscape() {
		return key == Keyboard.KEY_ESCAPE;
	}

	public boolean isLeft() {
		return key == Keyboard.KEY_LEFT;
	}

	public boolean isRight() {
		return key == Keyboard.KEY_RIGHT;
	}

	public boolean isUp() {
		return key == Keyboard.KEY_UP;
	}

	public boolean isDown() {
		return key == Keyboard.KEY_DOWN;
	}

	public boolean hasShiftDown() {
		return (modifiers & SHIFT) != 0;
	}

	public boolean hasAltDown() {
		return (modifiers & ALT) != 0;
	}

	/** The edit shortcut modifier: Control, or Command on macOS. */
	public boolean hasControlDownWithQuirk() {
		return (modifiers & (MAC ? META : CONTROL)) != 0;
	}

	private boolean shortcut(int letter) {
		return key == letter && hasControlDownWithQuirk() && !hasShiftDown() && !hasAltDown();
	}

	public boolean isSelectAll() {
		return shortcut(Keyboard.KEY_A);
	}

	public boolean isCopy() {
		return shortcut(Keyboard.KEY_C);
	}

	public boolean isPaste() {
		return shortcut(Keyboard.KEY_V);
	}

	public boolean isCut() {
		return shortcut(Keyboard.KEY_X);
	}
}
