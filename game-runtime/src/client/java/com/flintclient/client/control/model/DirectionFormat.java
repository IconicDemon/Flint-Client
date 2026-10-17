package com.flintclient.client.control.model;

/** How much the direction module says: "N", "North", or "North · −Z · 172°". */
public enum DirectionFormat implements Labelled {
	COMPACT("Compact"),
	NAME("Name"),
	DETAILED("Detailed");

	private final String label;

	DirectionFormat(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
