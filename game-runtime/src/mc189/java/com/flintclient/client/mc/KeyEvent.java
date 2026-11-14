package com.flintclient.client.mc;

import org.lwjgl.input.Keyboard;

/**
 * A key press as newer Minecraft hands it to a screen, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}): the key
 * as LWJGL 2 numbers it ({@link InputConstants}), and the modifier keys held (newer Minecraft's bits: 1 Shift, 2 Control, 4 Alt).
 */
public record KeyEvent(int key, int scancode, int modifiers) implements InputWithModifiers {
	static final int SHIFT = 1;
	static final int CONTROL = 2;
	static final int ALT = 4;

	/** A press of {@code key} with the modifiers held now. */
	public static KeyEvent of(int key) {
		return new KeyEvent(key, 0, modifiersNow());
	}

	static int modifiersNow() {
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
		return m;
	}

	public boolean isEscape() {
		return key == Keyboard.KEY_ESCAPE;
	}

	public boolean isCycleFocus() {
		return key == Keyboard.KEY_TAB;
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

	/** Enter or Space: presses the focused control. */
	public boolean isSelection() {
		return key == Keyboard.KEY_RETURN || key == Keyboard.KEY_SPACE || key == Keyboard.KEY_NUMPADENTER;
	}

	public boolean isConfirmation() {
		return key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER;
	}

	public boolean hasShiftDown() {
		return (modifiers & SHIFT) != 0;
	}

	public boolean hasControlDown() {
		return (modifiers & CONTROL) != 0;
	}

	public boolean hasAltDown() {
		return (modifiers & ALT) != 0;
	}
}
