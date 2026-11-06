package com.flintclient.client.compat.input;

/** A mouse button and the modifiers held with it, as 1.21.9's {@code MouseButtonInfo}. */
public record MouseButtonInfo(int button, int modifiers) implements InputWithModifiers {
	@Override
	public int input() {
		return button;
	}
}
