package com.flintclient.client.control.model;

/**
 * The Control Center's interface size relative to Minecraft's GUI scale. It is a request, not a promise: a
 * larger size is used only when the screen's minimum layout still fits the window at that scale.
 */
public enum InterfaceSize implements Labelled {
	SMALLER("Smaller", -1),
	AUTO("Auto", 0),
	LARGER("Larger", 1);

	private final String label;
	private final int delta;

	InterfaceSize(String label, int delta) {
		this.label = label;
		this.delta = delta;
	}

	@Override
	public String label() {
		return label;
	}

	/** Steps away from Minecraft's GUI scale. */
	public int delta() {
		return delta;
	}

	/**
	 * The UI scale to draw at: Minecraft's GUI scale plus this size's step, never below 1 and never above
	 * {@code fit} (see {@link #fit}).
	 */
	public int uiScale(int guiScale, int fit) {
		return Math.clamp(guiScale + delta, 1, Math.max(1, fit));
	}

	/**
	 * The largest whole scale at which a layout of {@code minWidth × minHeight} UI units fits a window of
	 * {@code windowWidthPx × windowHeightPx} physical pixels: the "safe interface scale". At least 1, even
	 * when scale 1 doesn't fit (the layout then shrinks to the window).
	 */
	public static int fit(int windowWidthPx, int windowHeightPx, int minWidth, int minHeight) {
		if (minWidth <= 0 || minHeight <= 0) {
			return 1;
		}
		return Math.max(1, Math.min(windowWidthPx / minWidth, windowHeightPx / minHeight));
	}
}
