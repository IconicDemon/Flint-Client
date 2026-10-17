package com.flintclient.client.control.model;

/** The library's categories, in the order their chips appear. */
public enum Category implements Labelled {
	PERFORMANCE("Performance"),
	COMBAT("Combat"),
	WORLD("World"),
	VISUAL("Visual"),
	UTILITY("Utility");

	private final String label;

	Category(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
