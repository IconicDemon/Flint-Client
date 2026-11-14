package com.flintclient.client.compat;

/**
 * 1.20's last kind of input ({@code net.minecraft.client.InputType}) on Minecraft 1.19.2, which doesn't track it: the keyboard
 * once Tab or an arrow moves the focus, the mouse once a button is pressed (mixin.bridge.LastInputMixin and
 * LastMouseInputMixin set it; {@code Minecraft.getLastInputType()} reads it through Bridges.Game).
 */
public enum InputType {
	NONE,
	MOUSE,
	KEYBOARD_ARROW,
	KEYBOARD_TAB;

	private static volatile InputType last = NONE;

	public boolean isKeyboard() {
		return this == KEYBOARD_ARROW || this == KEYBOARD_TAB;
	}

	public boolean isMouse() {
		return this == MOUSE;
	}

	public static InputType last() {
		return last;
	}

	public static void set(InputType type) {
		last = type;
	}
}
