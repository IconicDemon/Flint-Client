package com.flintclient.client.control.model;

/** Decimals of the frame rate. The labels are samples, so the choice explains itself. */
public enum FpsDecimals implements Labelled {
	NONE("0", 0),
	ONE("0.0", 1);

	private final String label;
	private final int places;

	FpsDecimals(String label, int places) {
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
