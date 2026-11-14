package com.flintclient.client.mc;

import com.flintclient.client.platform.Frame;

/** The game window as newer Minecraft names it, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}). */
public final class Window {
	Window() {
	}

	/** Its width in pixels. */
	public int getWidth() {
		return Minecraft.game().width;
	}

	public int getHeight() {
		return Minecraft.game().height;
	}

	/** Pixels per GUI unit (1.8.9's GUI scale as it works it out). */
	public int getGuiScale() {
		return Frame.windowGuiScale();
	}

	/** Its width in GUI units (1.8.9's scaled resolution: the pixels over the GUI scale, rounded up). */
	public int getGuiScaledWidth() {
		return (int) Math.ceil(getWidth() / (double) Math.max(1, getGuiScale()));
	}

	public int getGuiScaledHeight() {
		return (int) Math.ceil(getHeight() / (double) Math.max(1, getGuiScale()));
	}
}
