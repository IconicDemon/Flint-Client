package com.flintclient.client.control.model;

/** How the CPS module writes both counts. The labels are samples (left 8, right 3). */
public enum CpsFormat implements Labelled {
	DOT("8 · 3"),
	BAR("8 | 3"),
	LETTERS("L 8  R 3");

	private final String label;

	CpsFormat(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
