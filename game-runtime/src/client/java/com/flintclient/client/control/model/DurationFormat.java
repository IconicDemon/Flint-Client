package com.flintclient.client.control.model;

/** How the effects module writes the time left. The labels are samples (a minute and a half). */
public enum DurationFormat implements Labelled {
	CLOCK("1:30"),
	SECONDS("90s");

	private final String label;

	DurationFormat(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
