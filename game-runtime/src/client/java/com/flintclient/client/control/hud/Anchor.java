package com.flintclient.client.control.hud;

/**
 * The point of the screen a HUD module is pinned to. An element keeps its distance to that edge (or to the
 * centre) when the window is resized, so a module in the top-right corner stays there at any size.
 * {@link #fx}/{@link #fy} say where the anchor sits (0 = left/top, 0.5 = centre, 1 = right/bottom).
 */
public enum Anchor {
	TOP_LEFT(0.0F, 0.0F),
	TOP(0.5F, 0.0F),
	TOP_RIGHT(1.0F, 0.0F),
	LEFT(0.0F, 0.5F),
	CENTER(0.5F, 0.5F),
	RIGHT(1.0F, 0.5F),
	BOTTOM_LEFT(0.0F, 1.0F),
	BOTTOM(0.5F, 1.0F),
	BOTTOM_RIGHT(1.0F, 1.0F);

	private static final Anchor[] VALUES = values();

	private final float fx;
	private final float fy;

	Anchor(float fx, float fy) {
		this.fx = fx;
		this.fy = fy;
	}

	/** Horizontal position as a fraction of the free width: 0, 0.5 or 1. */
	public float fx() {
		return fx;
	}

	/** Vertical position as a fraction of the free height: 0, 0.5 or 1. */
	public float fy() {
		return fy;
	}

	/**
	 * Which way a positive x offset moves the element: inward from the anchored edge, so right for left and
	 * centre anchors and left for right anchors.
	 */
	public float signX() {
		return fx == 1.0F ? -1.0F : 1.0F;
	}

	/** As {@link #signX}, for y: down for top and middle anchors, up for bottom anchors. */
	public float signY() {
		return fy == 1.0F ? -1.0F : 1.0F;
	}

	/** The anchor at column {@code col} and row {@code row}, each 0 (left/top), 1 (centre) or 2 (right/bottom). */
	public static Anchor of(int col, int row) {
		return VALUES[Math.clamp(row, 0, 2) * 3 + Math.clamp(col, 0, 2)];
	}

	/** The anchor for an element centred at {@code (cx, cy)}: the third of the screen it falls in on each axis. */
	public static Anchor forCenter(float cx, float cy, float screenWidth, float screenHeight) {
		return of(third(cx, screenWidth), third(cy, screenHeight));
	}

	private static int third(float centre, float size) {
		if (centre < size / 3.0F) {
			return 0;
		}
		return centre > size * 2.0F / 3.0F ? 2 : 1;
	}
}
