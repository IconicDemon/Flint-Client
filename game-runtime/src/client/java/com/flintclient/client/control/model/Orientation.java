package com.flintclient.client.control.model;

/** Whether a multi-part module stacks its parts or lines them up. */
public enum Orientation implements Labelled {
	VERTICAL("Vertical"),
	HORIZONTAL("Horizontal");

	private final String label;

	Orientation(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
