package com.flintclient.client.ui.kit;

/**
 * Places its visible children left to right at their preferred size, wrapping onto new rows; each row
 * is as tall as its tallest child and children are centred in it.
 */
public class Flow extends Node {
	private final float gapX;
	private final float gapY;

	public Flow(float gapX, float gapY) {
		this.gapX = gapX;
		this.gapY = gapY;
	}

	@Override
	protected void layout() {
		arrange(w, true);
	}

	@Override
	public float prefHeight(float width) {
		return arrange(width, false);
	}

	/** Lays the rows out (or only measures them); returns the total height. */
	private float arrange(float width, boolean place) {
		float left = 0;
		float top = 0;
		float rowH = 0;
		int rowStart = 0;
		for (int i = 0; i <= childCount(); i++) {
			Node child = i < childCount() ? child(i) : null;
			if (child != null && !child.visible()) {
				continue;
			}
			float cw = child == null ? 0 : Math.min(width, child.prefWidth());
			if (child == null || (left > 0 && left + cw > width)) {
				if (place) {
					placeRow(rowStart, i, top, rowH);
				}
				if (child == null) {
					break;
				}
				top += rowH + gapY;
				left = 0;
				rowH = 0;
				rowStart = i;
			}
			if (place) {
				child.setBounds(x + left, y + top, cw, child.prefHeight(cw));
			}
			left += cw + gapX;
			rowH = Math.max(rowH, child.prefHeight(cw));
		}
		return top + rowH;
	}

	private void placeRow(int from, int to, float top, float rowH) {
		for (int i = from; i < to; i++) {
			Node child = child(i);
			if (child.visible()) {
				child.offset(0, (rowH - child.height()) / 2);
			}
		}
	}
}
