package com.flintclient.client.control.model;

/**
 * Whether the coordinates stack X, Y and Z or put them on one line. The constant names match {@link Orientation}'s,
 * which this setting used before, so files keep reading the same.
 */
public enum CoordinatesLayout implements Labelled {
	VERTICAL("Stacked"),
	HORIZONTAL("Single line");

	private final String label;

	CoordinatesLayout(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
