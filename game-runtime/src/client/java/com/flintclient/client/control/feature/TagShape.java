package com.flintclient.client.control.feature;

import java.util.List;

/**
 * A nametag's (or chat bubble's) background as rectangles that never overlap, so a see-through colour stays even: the
 * fill, its corners square or rounded, and the border ring around it. Rows are {@link #STEP} tall in the corners; rows
 * alike are merged. Pure Java.
 */
public final class TagShape {
	/** The height of a corner row, in text pixels. */
	public static final float STEP = 0.5F;

	private TagShape() {
	}

	/**
	 * Adds {@code {x0, y0, x1, y1}} rectangles to {@code fill} for the box {@code left..right} by {@code top..bottom} with
	 * corners of {@code radius} (0: square), and to {@code border} for a ring {@code thickness} wide around it (0: none).
	 */
	public static void build(float left, float top, float right, float bottom, float radius, float thickness, List<float[]> fill,
		List<float[]> border) {
		float r = Math.max(0, Math.min(radius, Math.min(right - left, bottom - top) / 2));
		float t = Math.max(0, thickness);
		float oTop = top - t;
		float oBottom = bottom + t;
		float oRadius = r > 0 ? r + t : 0;
		float[] lastFill = null;
		float[] lastLeft = null;
		float[] lastRight = null;
		float[] lastWhole = null;
		int rows = Math.max(1, Math.round((oBottom - oTop) / STEP));
		for (int i = 0; i < rows; i++) {
			float y0 = oTop + i * STEP;
			float y1 = i == rows - 1 ? oBottom : oTop + (i + 1) * STEP;
			float mid = (y0 + y1) / 2;
			float oInset = inset(mid, oTop, oBottom, oRadius);
			float oLeft = left - t + oInset;
			float oRight = right + t - oInset;
			boolean inside = mid > top && mid < bottom;
			if (inside) {
				float iInset = inset(mid, top, bottom, r);
				float iLeft = left + iInset;
				float iRight = right - iInset;
				lastFill = add(fill, lastFill, iLeft, y0, iRight, y1);
				if (t > 0) {
					lastLeft = add(border, lastLeft, oLeft, y0, iLeft, y1);
					lastRight = add(border, lastRight, iRight, y0, oRight, y1);
				}
				lastWhole = null;
			} else if (t > 0) {
				lastWhole = add(border, lastWhole, oLeft, y0, oRight, y1);
				lastLeft = null;
				lastRight = null;
			}
		}
	}

	/** How far a rounded box's edge sits in from its side at height {@code y}. */
	private static float inset(float y, float top, float bottom, float radius) {
		if (radius <= 0) {
			return 0;
		}
		float dy = y < top + radius ? top + radius - y : y > bottom - radius ? y - (bottom - radius) : 0;
		return radius - (float) Math.sqrt(Math.max(0, radius * radius - dy * dy));
	}

	/** Grows {@code last} down when the new row has its sides, else adds the row; returns the rectangle it went in. */
	private static float[] add(List<float[]> out, float[] last, float x0, float y0, float x1, float y1) {
		if (x1 - x0 < 1e-4F) {
			return null;
		}
		if (last != null && Math.abs(last[0] - x0) < 1e-4F && Math.abs(last[2] - x1) < 1e-4F && Math.abs(last[3] - y0) < 1e-4F) {
			last[3] = y1;
			return last;
		}
		float[] rect = {x0, y0, x1, y1};
		out.add(rect);
		return rect;
	}
}
