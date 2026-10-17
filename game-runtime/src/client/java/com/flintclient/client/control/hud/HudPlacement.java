package com.flintclient.client.control.hud;

/**
 * Where a HUD module sits: an {@link Anchor}, an offset from it and a size step, all in GUI units. Offsets
 * are measured inward from the anchored edge (so {@code TOP_RIGHT (4, 4)} is 4 units from the right and top
 * edges) and from the centre for centre anchors (positive is right/down). A module therefore keeps its
 * relation to its corner or edge whatever the window size.
 *
 * <p>Methods taking {@code drawnWidth}/{@code drawnHeight} want the size the module covers on screen, in GUI
 * units with {@link #scale} already applied. The {@code left}/{@code top} methods are allocation-free for use
 * every frame.
 */
public record HudPlacement(Anchor anchor, float x, float y, float scale) {
	public HudPlacement {
		if (anchor == null) {
			anchor = Anchor.TOP_LEFT;
		}
		// Unusable offsets become 0, and -0 (from flipping a right/bottom offset) becomes 0 so equal placements are equal.
		x = Float.isFinite(x) && x != 0.0F ? x : 0.0F;
		y = Float.isFinite(y) && y != 0.0F ? y : 0.0F;
		scale = ScaleSteps.snap(scale);
	}

	/** A placement at the default size. */
	public static HudPlacement of(Anchor anchor, float x, float y) {
		return new HudPlacement(anchor, x, y, ScaleSteps.DEFAULT);
	}

	/** The left edge on a screen {@code screenWidth} wide; may be off-screen (see {@link #clampedLeft}). */
	public float left(float screenWidth, float drawnWidth) {
		return anchor.fx() * (screenWidth - drawnWidth) + anchor.signX() * x;
	}

	/** The top edge on a screen {@code screenHeight} tall; may be off-screen (see {@link #clampedTop}). */
	public float top(float screenHeight, float drawnHeight) {
		return anchor.fy() * (screenHeight - drawnHeight) + anchor.signY() * y;
	}

	/** The left edge, moved just enough to keep the module on screen (at 0 when it is wider than the screen). */
	public float clampedLeft(float screenWidth, float drawnWidth) {
		return clamp(left(screenWidth, drawnWidth), screenWidth - drawnWidth);
	}

	/** The top edge, moved just enough to keep the module on screen (at 0 when it is taller than the screen). */
	public float clampedTop(float screenHeight, float drawnHeight) {
		return clamp(top(screenHeight, drawnHeight), screenHeight - drawnHeight);
	}

	/** The module's rectangle, unclamped. */
	public HudRect toAbsolute(float screenWidth, float screenHeight, float drawnWidth, float drawnHeight) {
		return new HudRect(left(screenWidth, drawnWidth), top(screenHeight, drawnHeight), drawnWidth, drawnHeight);
	}

	/** The module's rectangle as drawn: clamped fully on screen. */
	public HudRect onScreen(float screenWidth, float screenHeight, float drawnWidth, float drawnHeight) {
		return new HudRect(clampedLeft(screenWidth, drawnWidth), clampedTop(screenHeight, drawnHeight), drawnWidth, drawnHeight);
	}

	/** This placement with its offsets changed (same anchor) so that the module lies fully on screen. */
	public HudPlacement clampOnScreen(float screenWidth, float screenHeight, float drawnWidth, float drawnHeight) {
		float left = clampedLeft(screenWidth, drawnWidth);
		float top = clampedTop(screenHeight, drawnHeight);
		return at(anchor, left, top, screenWidth, screenHeight, drawnWidth, drawnHeight, scale);
	}

	/**
	 * The placement for a module drawn at {@code (left, top)}, anchored to the third of the screen its centre
	 * is in (left/centre/right × top/middle/bottom), with offsets that keep it exactly where it is.
	 */
	public static HudPlacement fromAbsolute(float left, float top, float drawnWidth, float drawnHeight, float screenWidth,
		float screenHeight, float scale) {
		Anchor anchor = Anchor.forCenter(left + drawnWidth / 2.0F, top + drawnHeight / 2.0F, screenWidth, screenHeight);
		return at(anchor, left, top, screenWidth, screenHeight, drawnWidth, drawnHeight, scale);
	}

	/** The placement with {@code anchor} that puts a module at {@code (left, top)}. */
	public static HudPlacement at(Anchor anchor, float left, float top, float screenWidth, float screenHeight, float drawnWidth,
		float drawnHeight, float scale) {
		float x = (left - anchor.fx() * (screenWidth - drawnWidth)) * anchor.signX();
		float y = (top - anchor.fy() * (screenHeight - drawnHeight)) * anchor.signY();
		return new HudPlacement(anchor, x, y, scale);
	}

	/** This placement re-anchored by its current position (see {@link #fromAbsolute}); the module doesn't move. */
	public HudPlacement reanchored(float screenWidth, float screenHeight, float drawnWidth, float drawnHeight) {
		return fromAbsolute(left(screenWidth, drawnWidth), top(screenHeight, drawnHeight), drawnWidth, drawnHeight, screenWidth,
			screenHeight, scale);
	}

	/** Moved by {@code (dx, dy)} on screen (positive is right/down, whatever the anchor). */
	public HudPlacement moved(float dx, float dy) {
		return new HudPlacement(anchor, x + anchor.signX() * dx, y + anchor.signY() * dy, scale);
	}

	public HudPlacement withScale(float newScale) {
		return new HudPlacement(anchor, x, y, newScale);
	}

	public HudPlacement withOffset(float newX, float newY) {
		return new HudPlacement(anchor, newX, newY, scale);
	}

	private static float clamp(float value, float max) {
		return max <= 0 ? 0.0F : Math.clamp(value, 0.0F, max);
	}
}
