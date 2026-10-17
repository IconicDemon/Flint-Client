package com.flintclient.client.control.model;

/** How often the FPS module's number changes. The meter refreshes every quarter second; this picks every n-th. */
public enum FpsInterval implements Labelled {
	QUARTER_SECOND("0.25 s", 1),
	HALF_SECOND("0.5 s", 2),
	ONE_SECOND("1 s", 4);

	private final String label;
	private final int refreshes;

	FpsInterval(String label, int refreshes) {
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
