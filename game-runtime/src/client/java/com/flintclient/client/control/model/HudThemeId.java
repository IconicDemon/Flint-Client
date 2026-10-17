package com.flintclient.client.control.model;

/**
 * The three HUD styles. Saved by lower-case name ({@code "flint"}). Each brings its own panel defaults, used
 * wherever neither the module nor the layout sets them: Flint draws a panel at 70 % opacity, Classic a panel at
 * 40 % (like Minecraft's chat background) and Minimal no panel at all.
 */
public enum HudThemeId implements Labelled {
	FLINT("Flint", true, 70),
	CLASSIC("Classic", true, 40),
	MINIMAL("Minimal", false, 70);

	private final String label;
	private final boolean background;
	private final int backgroundOpacity;

	HudThemeId(String label, boolean background, int backgroundOpacity) {
		this.label = label;
		this.background = background;
		this.backgroundOpacity = backgroundOpacity;
	}

	@Override
	public String label() {
		return label;
	}

	/** Whether this style draws a panel behind a module unless told otherwise. */
	public boolean defaultBackground() {
		return background;
	}

	/** The panel's opacity in percent (0..100, a multiple of 5) unless told otherwise. */
	public int defaultBackgroundOpacity() {
		return backgroundOpacity;
	}
}
