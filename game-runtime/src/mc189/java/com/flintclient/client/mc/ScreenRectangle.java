package com.flintclient.client.mc;

/** A widget's area in its screen's units, as newer Minecraft names it, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}). */
public record ScreenRectangle(int left, int top, int width, int height) {
	public int right() {
		return left + width;
	}

	public int bottom() {
		return top + height;
	}
}
