package com.flintclient.client.control.model;

/** Which clicks the CPS module counts. Combined is left and right added together. */
public enum CpsButtons implements Labelled {
	LEFT("Left"),
	RIGHT("Right"),
	BOTH("Both"),
	COMBINED("Combined");

	private final String label;

	CpsButtons(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
