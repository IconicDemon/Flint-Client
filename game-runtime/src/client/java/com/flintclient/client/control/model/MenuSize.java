package com.flintclient.client.control.model;

/**
 * General › Menu size: the Right Shift menu and every Flint window drawn like it (the Library, Cosmetics, Friends...) in three
 * steps. Large is the size before 1.0.4; Medium, a little smaller, is the default; Small the least. Each is a share of the size
 * the window gives the menu, which still never goes below the room Minecraft's 2× font needs (so on small windows the steps
 * meet). Saved by lower-case name ({@code "medium"}). Pure.
 */
public enum MenuSize implements Labelled {
	SMALL("Small", 0.8F),
	MEDIUM("Medium", 0.9F),
	LARGE("Large", 1.0F);

	private final String label;
	private final float zoom;

	MenuSize(String label, float zoom) {
		this.label = label;
		this.zoom = zoom;
	}

	@Override
	public String label() {
		return label;
	}

	/** The share of the window-fitted size the menu takes. */
	public float zoom() {
		return zoom;
	}
}
