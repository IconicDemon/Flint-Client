package com.flintclient.client.control.model;

/** How wide the FPS counter's box is: a width of the player's choosing, or just wide enough for the text. */
public enum FpsSizing implements Labelled {
	STATIC("Static"),
	DYNAMIC("Dynamic");

	private final String label;

	FpsSizing(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
