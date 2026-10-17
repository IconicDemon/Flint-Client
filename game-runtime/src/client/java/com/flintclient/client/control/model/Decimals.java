package com.flintclient.client.control.model;

/** How many decimals the coordinates show. The labels are samples, so the choice explains itself. */
public enum Decimals implements Labelled {
	NONE("0", 0),
	ONE("0.0", 1),
	TWO("0.00", 2);

	private final String label;
	private final int places;

	Decimals(String label, int places) {
		this.label = label;
		this.places = places;
	}

	@Override
	public String label() {
		return label;
	}

	public int places() {
		return places;
	}
}
