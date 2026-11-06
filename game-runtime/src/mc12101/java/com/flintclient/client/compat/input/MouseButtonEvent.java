package com.flintclient.client.compat.input;

/** A mouse button at a GUI position, as 1.21.9's {@code MouseButtonEvent}. */
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) implements InputWithModifiers {
	@Override
	public int input() {
		return button();
	}

	public int button() {
		return buttonInfo.button();
	}

	@Override
	public int modifiers() {
		return buttonInfo.modifiers();
	}
}
