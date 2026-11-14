package com.flintclient.client.mc;

import org.lwjgl.input.Keyboard;

/** Newer Minecraft's key numbers Flint's main menu names, as Minecraft 1.8.9 (LWJGL 2) numbers the same keys (see {@link Component}). */
public final class InputConstants {
	public static final int KEY_ESCAPE = Keyboard.KEY_ESCAPE;
	public static final int KEY_TAB = Keyboard.KEY_TAB;
	public static final int KEY_RETURN = Keyboard.KEY_RETURN;
	public static final int KEY_NUMPADENTER = Keyboard.KEY_NUMPADENTER;
	public static final int KEY_SPACE = Keyboard.KEY_SPACE;
	public static final int KEY_UP = Keyboard.KEY_UP;
	public static final int KEY_DOWN = Keyboard.KEY_DOWN;
	public static final int KEY_LEFT = Keyboard.KEY_LEFT;
	public static final int KEY_RIGHT = Keyboard.KEY_RIGHT;
	public static final int KEY_PAGEUP = Keyboard.KEY_PRIOR;
	public static final int KEY_PAGEDOWN = Keyboard.KEY_NEXT;
	public static final int KEY_HOME = Keyboard.KEY_HOME;
	public static final int KEY_END = Keyboard.KEY_END;

	private InputConstants() {
	}
}
