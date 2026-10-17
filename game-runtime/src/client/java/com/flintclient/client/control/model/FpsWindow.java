package com.flintclient.client.control.model;

/** How far back the FPS module averages: a longer window reads steadier and reacts more slowly. */
public enum FpsWindow implements Labelled {
	HALF_SECOND("0.5 s", 2),
	ONE_SECOND("1 s", 4),
	TWO_SECONDS("2 s", 8);

	private final String label;
	private final int refreshes;

	FpsWindow(String label, int refreshes) {
		this.label = label;
		this.refreshes = refreshes;
	}

	@Override
	public String label() {
		return label;
	}

	/** In quarter-second meter refreshes. */
	public int refreshes() {
		return refreshes;
	}
}
