package com.flintclient.client.control.editor;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.hud.render.HudScene;

/**
 * The HUD editor's small decisions, kept free of Minecraft so they can be tested: where the inspector goes,
 * how dragging the corner handle maps to a size step, how far a key nudges, how dense the dot grid is, and
 * rectangle checks. Units are GUI units unless a name says pixels.
 */
public final class EditorMath {
	/** Arrow keys move the selected module this far; with Shift, {@link #NUDGE_FAR}. */
	public static final float NUDGE = 1.0F;
	public static final float NUDGE_FAR = 10.0F;
	/** Shift+arrow: a precise half unit. */
	public static final float NUDGE_FINE = 0.5F;
	/** The dot grid's spacing. */
	public static final float GRID = 16.0F;
	/** A press must move this far before it counts as a drag (so a click to select never nudges). */
	public static final float DRAG_START = 1.5F;

	private EditorMath() {
	}

	/**
	 * Whether the inspector sheet goes on the left: when the selected module's centre is in the right half of
	 * the screen, so the sheet never covers what it edits.
	 */
	public static boolean inspectorOnLeft(float moduleCenterX, float screenWidth) {
		return moduleCenterX > screenWidth / 2.0F;
	}

	/**
	 * The size step after dragging the corner handle: the size at the press, scaled by how much farther the
	 * pointer now is from the module's fixed top-left corner than it was at the press, snapped to the nearest
	 * step. A pointer at or behind the corner gives the smallest step.
	 */
	public static float scaleForDrag(float startScale, float startDistance, float distance) {
		if (startDistance <= 0.0F || !Float.isFinite(distance)) {
			return ScaleSteps.snap(startScale);
		}
		float wanted = startScale * Math.max(0.0F, distance) / startDistance;
		return ScaleSteps.snap(Math.clamp(wanted, ScaleSteps.min(), ScaleSteps.max()));
	}

	/** The distance from {@code (x0, y0)} to {@code (x1, y1)}. */
	public static float distance(float x0, float y0, float x1, float y1) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	/** An arrow key's step: 1 unit, 10 with Ctrl (a large move), half a unit with Shift (a precise one). */
	public static float nudge(boolean large, boolean precise) {
		return large ? NUDGE_FAR : precise ? NUDGE_FINE : NUDGE;
	}

	/**
	 * Whether a widget matches the palette's search: every word of {@code query} is in its name or its id (underscores
	 * read as spaces), ignoring case; an empty search matches everything.
	 */
	public static boolean widgetMatches(String query, String name, String id) {
		String text = (name + " " + id.replace('_', ' ')).toLowerCase(java.util.Locale.ROOT);
		for (String word : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+")) {
			if (!word.isEmpty() && !text.contains(word)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The grid's spacing in pixels: {@link #GRID} units at {@code unitPx} pixels per unit, whatever the screen
	 * size (the grid is one repeating tile, so a dense grid costs no more than a sparse one).
	 */
	public static int gridSpacingPx(int unitPx) {
		return Math.max(2, Math.round(GRID * Math.max(1, unitPx)));
	}

	/**
	 * The next module shown in {@code scene} after {@code from} in registry order ({@code direction} 1) or
	 * before it (-1), wrapping round; from nothing ({@code from} &lt; 0) the first or the last. -1 when none is
	 * shown. How the keyboard reaches every module.
	 */
	public static int nextShown(HudScene scene, int from, int direction) {
		int n = scene.size();
		int start = from >= 0 ? from : direction > 0 ? -1 : n;
		for (int k = 1; k <= n; k++) {
			int i = Math.floorMod(start + direction * k, n);
			if (scene.state(i) != HudScene.OFF) {
				return i;
			}
		}
		return -1;
	}

	/** Whether two rectangles share area (touching edges don't count). */
	public static boolean overlaps(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
		return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah;
	}

	/** Whether a point lies in a square of side {@code size} centred on {@code (cx, cy)}. */
	public static boolean inHandle(float px, float py, float cx, float cy, float size) {
		float half = size / 2.0F;
		return px >= cx - half && px <= cx + half && py >= cy - half && py <= cy + half;
	}

	/** {@code value} kept within {@code 0..max} (0 when the range is empty). */
	public static float clampInto(float value, float max) {
		return max <= 0.0F ? 0.0F : Math.clamp(value, 0.0F, max);
	}
}
