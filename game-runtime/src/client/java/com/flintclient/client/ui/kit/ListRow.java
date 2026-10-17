package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import org.jspecify.annotations.Nullable;

/**
 * A row in a list (a module, a layout): no card, just a hairline under it, and on hover a faint rounded overlay
 * (radius 5) that darkens a little while pressed. Rows reach {@link #BLEED} units past the list's column on each
 * side: the overlay sits 2 units inside the row's edges, so it frames the content with room to spare, while the
 * hairline keeps to the column. The whole row is one tab stop; its focus ring follows the overlay. Subclasses
 * draw their content in {@link #paintContent} (from {@link #contentX()}) and decide what a click does.
 */
public abstract class ListRow extends Pressable {
	/** How far rows reach past the list's column on each side. */
	public static final float BLEED = 6;
	/** The overlay's inset from the row's sides. */
	public static final float INSET = 2;
	private boolean separator = true;
	private boolean selected;

	protected ListRow(@Nullable Runnable action) {
		super(action);
	}

	/** Whether a hairline is drawn under the row (not under the last one). */
	public void setSeparator(boolean separator) {
		this.separator = separator;
	}

	/** Marks the row as the current one (a faint overlay without hover). */
	public void setSelected(boolean selected) {
		this.selected = selected;
	}

	public boolean selected() {
		return selected;
	}

	/** The list column's left edge (where content starts). */
	protected float contentX() {
		return x + BLEED;
	}

	/** The list column's right edge. */
	protected float contentRight() {
		return x + w - BLEED;
	}

	/** How strongly the row shows as pointed at, 0..1 (subclasses may widen it to their children). */
	protected float highlight() {
		return hover;
	}

	@Override
	protected final void paint(Canvas c) {
		int x0 = c.px(x + INSET);
		int x1 = c.px(x + w - INSET);
		int y0 = py0(c);
		int y1 = c.px(y + h);
		float lift = Math.max(highlight(), selected ? 1 : 0);
		if (lift > 0.01F || press > 0.01F) {
			int fill = Palette.lerp(Palette.withAlpha(Tokens2.HOVER, Math.max(lift, 0.01F)), Tokens2.PRESS, press);
			c.roundRect(x0, y0 + 1, x1 - x0, y1 - y0 - 1, c.px(Tokens2.RADIUS_ROW), fill);
		}
		if (separator) {
			c.fill(c.px(contentX()), y1 - 1, c.px(contentRight()), y1, Tokens2.hairline());
		}
		paintContent(c);
		Kit.focusRing(c, x + INSET, y + 0.25F, w - 2 * INSET, h - 0.5F, Tokens2.RADIUS_ROW, focus);
	}

	/** The row's own content (icon, text, values). */
	protected abstract void paintContent(Canvas c);
}
