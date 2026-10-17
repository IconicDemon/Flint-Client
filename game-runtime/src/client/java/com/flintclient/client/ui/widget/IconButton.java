package com.flintclient.client.ui.widget;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.function.IntSupplier;
import net.minecraft.network.chat.Component;

/**
 * A square icon-only control (top-right utility row). Its message is the accessible name: it's narrated
 * and shown as the tooltip. An optional badge shows a real count (for example incoming friend
 * requests); nothing is drawn when the count is zero.
 */
public final class IconButton extends FlintButton {
	private static final float ICON = 5.4F;

	private final Icon icon;
	private final IntSupplier badge;
	private final TextLine badgeText = new TextLine(FlintFonts.CAPS, "");
	/** The count {@link #badgeText} shows, so its text is only rebuilt when the count changes. */
	private int badgeCount;

	public IconButton(Component name, Icon icon, Runnable action) {
		this(name, icon, () -> 0, action);
	}

	public IconButton(Component name, Icon icon, IntSupplier badge, Runnable action) {
		super(name, action);
		this.icon = icon;
		this.badge = badge;
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover), Palette.SURFACE_PRESS, press);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
		// Unavailable controls keep a solid surface; only the icon fades.
		int ink = active ? Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(hover, focus)) : Palette.withAlpha(Palette.MUTED, 0.55F);
		c.panel(x, y, w, h, c.px(MenuButton.RADIUS), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);
		int size = c.px(ICON);
		c.icon(icon, x + (w - size) / 2, y + (h - size) / 2, size, ink);

		int count = active ? badge.getAsInt() : 0;
		if (count > 0) {
			if (count != badgeCount) {
				badgeCount = count;
				badgeText.set(count > 9 ? "9+" : Integer.toString(count));
			}
			int d = c.px(4.4F);
			int bw = Math.max(d, c.width(badgeText) + c.px(1.6F));
			int bx = x + w - bw + c.px(1.2F);
			int by = y - c.px(1.2F);
			c.roundRect(bx, by, bw, d, d / 2, Palette.ACCENT);
			c.text(badgeText, bx + (bw - c.width(badgeText)) / 2, c.baselineFor(badgeText, by + d / 2), Palette.ACCENT_INK);
		}
	}
}
