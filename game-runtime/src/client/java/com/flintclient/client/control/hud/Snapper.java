package com.flintclient.client.control.hud;

import java.util.Arrays;

/**
 * Snapping for the HUD editor. A dragged module's left edge, centre and right edge (and top, middle and bottom)
 * are pulled onto the nearest guide within {@link #THRESHOLD} GUI units, each axis on its own:
 * <ul>
 * <li>the screen edges, inset {@link #EDGE_INSET} units (left edge to the left inset, right edge to the right);</li>
 * <li>the screen's centre lines (the module's centre);</li>
 * <li>other modules' edges (either edge to either edge) and centres (centre to centre);</li>
 * <li>extra areas, such as Minecraft's own HUD elements, the same way as modules.</li>
 * </ul>
 * The nearest guide wins; on an exact tie the earlier source in that list wins (screen edges, screen centre,
 * modules in the order added, then areas). Holding the bypass key (Alt / Option) turns snapping off.
 *
 * <p>One instance is reused for the whole drag: {@link #begin}, add the rectangles, then {@link #snap} as often
 * as needed. Nothing is allocated after the internal arrays have grown to the number of rectangles.
 */
public final class Snapper {
	/** How close (GUI units) an edge must be to a guide to snap. */
	public static final float THRESHOLD = 4.0F;
	/** The screen-edge guides sit this far inside the screen. */
	public static final float EDGE_INSET = 2.0F;

	/** What a guide line came from. */
	public enum Source {
		SCREEN_EDGE,
		SCREEN_CENTER,
		MODULE,
		AREA
	}

	private float screenWidth;
	private float screenHeight;
	/** x, y, w, h per rectangle; modules first, then areas. */
	private float[] modules = new float[4 * 16];
	private int moduleCount;
	private float[] areas = new float[4 * 8];
	private int areaCount;

	private float left;
	private float top;
	private final Guide vertical = new Guide();
	private final Guide horizontal = new Guide();

	/** Starts a new set of guides for a screen of this size. */
	public void begin(float screenWidth, float screenHeight) {
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		moduleCount = 0;
		areaCount = 0;
		vertical.clear();
		horizontal.clear();
	}

	/** Another module's rectangle (not the one being dragged). */
	public void addModule(float x, float y, float width, float height) {
		modules = put(modules, moduleCount++, x, y, width, height);
	}

	/** An extra area to align with, such as Minecraft's hotbar. */
	public void addArea(float x, float y, float width, float height) {
		areas = put(areas, areaCount++, x, y, width, height);
	}

	/**
	 * Snaps a module at {@code (left, top)} of the given size. Read the result from {@link #left()},
	 * {@link #top()} and the guide getters. Returns whether either axis snapped.
	 */
	public boolean snap(float left, float top, float width, float height, boolean bypass) {
		this.left = left;
		this.top = top;
		vertical.clear();
		horizontal.clear();
		if (bypass) {
			return false;
		}
		// Vertical guides (x positions) move the module horizontally.
		vertical.begin(THRESHOLD);
		vertical.consider(left, EDGE_INSET, Source.SCREEN_EDGE, 0, screenHeight);
		vertical.consider(left + width, screenWidth - EDGE_INSET, Source.SCREEN_EDGE, 0, screenHeight);
		vertical.consider(left + width / 2.0F, screenWidth / 2.0F, Source.SCREEN_CENTER, 0, screenHeight);
		considerRects(vertical, modules, moduleCount, Source.MODULE, left, width, 0);
		considerRects(vertical, areas, areaCount, Source.AREA, left, width, 0);

		horizontal.begin(THRESHOLD);
		horizontal.consider(top, EDGE_INSET, Source.SCREEN_EDGE, 0, screenWidth);
		horizontal.consider(top + height, screenHeight - EDGE_INSET, Source.SCREEN_EDGE, 0, screenWidth);
		horizontal.consider(top + height / 2.0F, screenHeight / 2.0F, Source.SCREEN_CENTER, 0, screenWidth);
		considerRects(horizontal, modules, moduleCount, Source.MODULE, top, height, 1);
		considerRects(horizontal, areas, areaCount, Source.AREA, top, height, 1);

		if (vertical.found) {
			this.left = left + vertical.delta;
		}
		if (horizontal.found) {
			this.top = top + horizontal.delta;
		}
		// A guide that came from a rectangle spans from it to the snapped module.
		if (vertical.found && vertical.rect >= 0) {
			float[] rects = vertical.source == Source.MODULE ? modules : areas;
			vertical.from = Math.min(this.top, rects[vertical.rect * 4 + 1]);
			vertical.to = Math.max(this.top + height, rects[vertical.rect * 4 + 1] + rects[vertical.rect * 4 + 3]);
		}
		if (horizontal.found && horizontal.rect >= 0) {
			float[] rects = horizontal.source == Source.MODULE ? modules : areas;
			horizontal.from = Math.min(this.left, rects[horizontal.rect * 4]);
			horizontal.to = Math.max(this.left + width, rects[horizontal.rect * 4] + rects[horizontal.rect * 4 + 2]);
		}
		return vertical.found || horizontal.found;
	}

	/** {@code axis} 0 compares x positions (vertical guides), 1 compares y positions (horizontal guides). */
	private static void considerRects(Guide guide, float[] rects, int count, Source source, float start, float size, int axis) {
		float end = start + size;
		float middle = start + size / 2.0F;
		for (int i = 0; i < count; i++) {
			float otherStart = rects[i * 4 + axis];
			float otherSize = rects[i * 4 + 2 + axis];
			float otherEnd = otherStart + otherSize;
			guide.consider(start, otherStart, source, i);
			guide.consider(start, otherEnd, source, i);
			guide.consider(end, otherStart, source, i);
			guide.consider(end, otherEnd, source, i);
			guide.consider(middle, otherStart + otherSize / 2.0F, source, i);
		}
	}

	/** The snapped left edge (the input when nothing snapped horizontally). */
	public float left() {
		return left;
	}

	/** The snapped top edge (the input when nothing snapped vertically). */
	public float top() {
		return top;
	}

	/** Whether a vertical guide line (constant x) is active. */
	public boolean hasVerticalGuide() {
		return vertical.found;
	}

	public float verticalGuideX() {
		return vertical.position;
	}

	/** Where the vertical guide line starts (y). */
	public float verticalGuideFrom() {
		return vertical.from;
	}

	/** Where the vertical guide line ends (y). */
	public float verticalGuideTo() {
		return vertical.to;
	}

	public Source verticalGuideSource() {
		return vertical.source;
	}

	/** Whether a horizontal guide line (constant y) is active. */
	public boolean hasHorizontalGuide() {
		return horizontal.found;
	}

	public float horizontalGuideY() {
		return horizontal.position;
	}

	/** Where the horizontal guide line starts (x). */
	public float horizontalGuideFrom() {
		return horizontal.from;
	}

	/** Where the horizontal guide line ends (x). */
	public float horizontalGuideTo() {
		return horizontal.to;
	}

	public Source horizontalGuideSource() {
		return horizontal.source;
	}

	private static float[] put(float[] rects, int index, float x, float y, float width, float height) {
		float[] target = index * 4 + 4 > rects.length ? Arrays.copyOf(rects, rects.length * 2) : rects;
		target[index * 4] = x;
		target[index * 4 + 1] = y;
		target[index * 4 + 2] = width;
		target[index * 4 + 3] = height;
		return target;
	}

	/** The best guide found so far on one axis. */
	private static final class Guide {
		boolean found;
		float best;
		float delta;
		float position;
		float from;
		float to;
		Source source = Source.SCREEN_EDGE;
		/** The rectangle index the guide came from, or -1 for a screen guide. */
		int rect;

		void clear() {
			found = false;
			rect = -1;
		}

		void begin(float threshold) {
			found = false;
			best = threshold;
			rect = -1;
		}

		/** A screen guide spanning {@code from..to}. */
		void consider(float edge, float guide, Source kind, float spanFrom, float spanTo) {
			if (take(edge, guide, kind)) {
				rect = -1;
				from = spanFrom;
				to = spanTo;
			}
		}

		/** A guide from rectangle {@code index}; its span is filled in after snapping. */
		void consider(float edge, float guide, Source kind, int index) {
			if (take(edge, guide, kind)) {
				rect = index;
			}
		}

		private boolean take(float edge, float guide, Source kind) {
			float distance = Math.abs(guide - edge);
			// Strictly nearer replaces; the first of equals stays. The threshold itself still snaps.
			if (found ? distance < best : distance <= best) {
				found = true;
				best = distance;
				delta = guide - edge;
				position = guide;
				source = kind;
				return true;
			}
			return false;
		}
	}
}
