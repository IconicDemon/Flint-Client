package com.flintclient.client.compat.input;

/** A key press or release as 1.21.9's {@code KeyEvent}: GLFW key code, scan code and modifiers. */
public record KeyEvent(int key, int scancode, int modifiers) implements InputWithModifiers {
	@Override
	public int input() {
		return key;
	}
}
