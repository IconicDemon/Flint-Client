package com.flintclient.client.control.model;

/** Which keys the keystrokes module shows: the movement keys, then the mouse buttons, then the jump key. */
public enum KeystrokesLayout implements Labelled {
	KEYS("Keys", false, false),
	KEYS_MOUSE("Keys + mouse", true, false),
	KEYS_MOUSE_JUMP("Keys, mouse and jump", true, true);

	private final String label;
	private final boolean mouse;
	private final boolean jump;

	KeystrokesLayout(String label, boolean mouse, boolean jump) {
		this.label = label;
		this.mouse = mouse;
		this.jump = jump;
	}

	@Override
	public String label() {
		return label;
	}

	public boolean mouse() {
		return mouse;
	}

	public boolean jump() {
		return jump;
	}

	/** The layout showing these parts; jump without the mouse buttons has no layout of its own and shows the keys only. */
	public static KeystrokesLayout of(boolean mouse, boolean jump) {
		if (!mouse) {
			return KEYS;
		}
		return jump ? KEYS_MOUSE_JUMP : KEYS_MOUSE;
	}
}
