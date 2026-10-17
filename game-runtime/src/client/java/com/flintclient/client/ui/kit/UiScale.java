package com.flintclient.client.ui.kit;

/**
 * The kit's interface scale (whole pixels per UI unit). Kit screens don't follow Minecraft's GUI scale: the
 * automatic scale is chosen from the window itself, {@code clamp(floor(min(pxW / 600, pxH / 340)), 1, 8)}, so a
 * screen is always roughly 600 to 1200 units wide (1080p gives 3, 1440p and 2560×1600 give 4, 1280×720 gives
 * 2). The "Interface size" setting moves it one step down or up; then it is limited so the screen still has at
 * least the screen's minimum layout ({@code minW × minH} units). If even scale 1 can't give that, it is 1. Pure,
 * for tests.
 */
public final class UiScale {
	/** Window pixels per unit of width and of height for the automatic scale. */
	public static final int AUTO_WIDTH = 600;
	public static final int AUTO_HEIGHT = 340;
	public static final int MAX = 8;

	private UiScale() {
	}

	/** The largest whole scale at which {@code minW × minH} units fit {@code windowW × windowH} pixels (0 if none). */
	public static int fit(int windowW, int windowH, int minW, int minH) {
		if (minW <= 0 || minH <= 0) {
			return Integer.MAX_VALUE;
		}
		return Math.max(0, Math.min(windowW / minW, windowH / minH));
	}

	/** The automatic scale for a window of {@code windowW × windowH} pixels. */
	public static int automatic(int windowW, int windowH) {
		return Math.clamp(Math.min(windowW / AUTO_WIDTH, windowH / AUTO_HEIGHT), 1, MAX);
	}

	/** The scale for the window with the interface size step {@code delta} (-1, 0, +1), keeping the minimum layout. */
	public static int compute(int delta, int windowW, int windowH, int minW, int minH) {
		int fit = Math.max(1, fit(windowW, windowH, minW, minH));
		return Math.clamp(automatic(windowW, windowH) + delta, 1, Math.min(MAX, fit));
	}
}
