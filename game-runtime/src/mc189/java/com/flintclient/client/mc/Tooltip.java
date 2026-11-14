package com.flintclient.client.mc;

/**
 * Newer Minecraft's widget tooltip, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}): Flint draws its own
 * tooltips ({@code ui.FlintTooltip}); this one only keeps the text, as newer Minecraft keeps it for the narrator (1.8.9 has none).
 */
public final class Tooltip {
	private final Component text;

	private Tooltip(Component text) {
		this.text = text;
	}

	public static Tooltip create(Component text) {
		return new Tooltip(text);
	}

	public Component text() {
		return text;
	}
}
