package com.flintclient.client.mc;

/**
 * A mouse press or release at ({@code x}, {@code y}) in the screen's units, as newer Minecraft hands it to a screen, for Flint's
 * main menu on Minecraft 1.8.9 (see {@link Component}). Buttons are numbered as LWJGL numbers them (0 left, 1 right, 2 middle).
 */
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) implements InputWithModifiers {
	public MouseButtonEvent(double x, double y, int button) {
		this(x, y, new MouseButtonInfo(button, KeyEvent.modifiersNow()));
	}

	public int button() {
		return buttonInfo.button();
	}
}
