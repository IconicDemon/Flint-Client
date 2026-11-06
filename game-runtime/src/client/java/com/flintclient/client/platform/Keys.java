package com.flintclient.client.platform;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * The keys and mouse buttons Flint's screens name, in this engine's numbering (Minecraft's {@link InputConstants}), and the
 * names of keys as players see them. {@link KeyInput#key()} and the mouse buttons screens receive are compared with these.
 * Platform class (see {@link Clock}).
 */
public final class Keys {
	public static final int TAB = InputConstants.KEY_TAB;
	public static final int BACKSPACE = InputConstants.KEY_BACKSPACE;
	public static final int DELETE = InputConstants.KEY_DELETE;
	public static final int HOME = InputConstants.KEY_HOME;
	public static final int END = InputConstants.KEY_END;
	public static final int PAGE_UP = InputConstants.KEY_PAGEUP;
	public static final int PAGE_DOWN = InputConstants.KEY_PAGEDOWN;
	public static final int RETURN = InputConstants.KEY_RETURN;
	public static final int NUMPAD_ENTER = InputConstants.KEY_NUMPADENTER;
	public static final int SPACE = InputConstants.KEY_SPACE;
	public static final int LEFT_SHIFT = InputConstants.KEY_LSHIFT;
	public static final int RIGHT_SHIFT = InputConstants.KEY_RSHIFT;
	public static final int LEFT_ALT = InputConstants.KEY_LALT;
	public static final int RIGHT_ALT = InputConstants.KEY_RALT;
	public static final int LEFT_BRACKET = InputConstants.KEY_LBRACKET;
	public static final int RIGHT_BRACKET = InputConstants.KEY_RBRACKET;
	/** Minecraft numbers mouse buttons from 1 (SDL): left 1, middle 2, right 3. */
	public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	public static final int MOUSE_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
	/** The first side button (the rest follow it). */
	public static final int MOUSE_4 = InputConstants.MOUSE_BUTTON_4;

	private Keys() {
	}

	/** A mouse button's stored name ({@code key.mouse.left}), as Flint's key settings keep it. */
	public static String mouseName(int button) {
		return InputConstants.Type.MOUSE.getOrCreate(button).getName();
	}

	/** What a stored key name ({@code key.keyboard.c}) shows as in Minecraft's Controls ("C"); the name itself if Minecraft doesn't know it. */
	public static String displayName(String keyName) {
		try {
			return InputConstants.getKey(keyName).getDisplayName().getString();
		} catch (RuntimeException | LinkageError unknown) {
			return keyName;
		}
	}
}
