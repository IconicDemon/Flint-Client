package com.flintclient.client.control.model;

/** Where text sits across a box that is wider than it. */
public enum TextAlign implements Labelled {
	LEFT("Left"),
	CENTER("Center"),
	RIGHT("Right");

	private final String label;

	TextAlign(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
