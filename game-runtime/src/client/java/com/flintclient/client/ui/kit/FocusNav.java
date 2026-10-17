package com.flintclient.client.ui.kit;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Keyboard focus movement over a list of focusable nodes in tab order: Tab and Shift+Tab step through the
 * list and wrap around; the arrow keys pick the nearest node in that direction (grids of cards, the
 * navigation rail), preferring nodes in line with the current one. Pure geometry, for tests.
 */
final class FocusNav {
	/** Added to the score of candidates not in line with the current node: they only win when none is. */
	private static final float OUT_OF_LINE = 1.0E6F;

	private FocusNav() {
	}

	/** The node after (or before) {@code current} in tab order, wrapping; the first (or last) when nothing is focused. */
	static @Nullable Node next(List<Node> order, @Nullable Node current, boolean forward) {
		int n = order.size();
		if (n == 0) {
			return null;
		}
		int index = current == null ? -1 : order.indexOf(current);
		if (index < 0) {
			return order.get(forward ? 0 : n - 1);
		}
		return order.get(Math.floorMod(index + (forward ? 1 : -1), n));
	}

	/**
	 * The nearest node from {@code current} in direction ({@code dx}, {@code dy}) (one of them ±1), or null.
	 * A candidate must lie beyond the current node's centre in that direction. Candidates in line with the
	 * current node (overlapping it sideways) always win over others, so the next card in the same row or
	 * column is chosen over a closer one diagonally; within each group the score is the gap along the
	 * direction plus twice the sideways offset.
	 */
	static @Nullable Node spatial(List<Node> order, Node current, int dx, int dy) {
		float cx = current.x + current.w / 2;
		float cy = current.y + current.h / 2;
		Node best = null;
		float bestScore = Float.MAX_VALUE;
		for (int i = 0; i < order.size(); i++) {
			Node candidate = order.get(i);
			if (candidate == current) {
				continue;
			}
			float ox = candidate.x + candidate.w / 2;
			float oy = candidate.y + candidate.h / 2;
			float along = (ox - cx) * dx + (oy - cy) * dy;
			if (along <= 0.5F) {
				continue;
			}
			float gap;
			float side;
			if (dx != 0) {
				gap = dx > 0 ? candidate.x - (current.x + current.w) : current.x - (candidate.x + candidate.w);
				side = overlapGap(current.y, current.h, candidate.y, candidate.h);
			} else {
				gap = dy > 0 ? candidate.y - (current.y + current.h) : current.y - (candidate.y + candidate.h);
				side = overlapGap(current.x, current.w, candidate.x, candidate.w);
			}
			float score = Math.max(0, gap) + 2 * side + (side > 0 ? OUT_OF_LINE : 0);
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	/** The distance between two ranges along one axis (0 when they overlap). */
	static float overlapGap(float a, float aLength, float b, float bLength) {
		if (b > a + aLength) {
			return b - (a + aLength);
		}
		if (a > b + bLength) {
			return a - (b + bLength);
		}
		return 0;
	}
}
