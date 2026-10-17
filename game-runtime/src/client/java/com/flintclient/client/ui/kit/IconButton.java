package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;

/**
 * An icon-only button: a square hit target (14, 16 or 22 units) with a smaller line icon centred in it, muted
 * until the pointer is over it. Its name is required: it is the tooltip and what the narrator says.
 * {@link #setActive} marks a toggled-on state in ember on a faint surface (the name says which state, never colour
 * alone).
 */
public class IconButton extends Pressable {
	public static final float TINY = 14;
	public static final float SMALL = 16;
	public static final float LARGE = 22;

	private Icon icon;
	private String name;
	private boolean active;
	private int tint;
	private float iconSize;
	private boolean surface = true;

	public IconButton(Icon icon, String name, float size, Runnable action) {
		super(action);
		this.icon = icon;
		this.name = name;
		this.w = size;
		this.h = size;
		this.iconSize = size >= LARGE ? 11 : size >= SMALL ? 9 : 8;
		tooltip(name);
	}

	public void setIcon(Icon icon) {
		this.icon = icon;
	}

	public void setName(String name) {
		this.name = name;
		tooltip(name);
	}

	public String buttonName() {
		return name;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	/** An icon colour for a special meaning (a warning), or 0 for the normal ink. */
	public void setTint(int argb) {
		this.tint = argb;
	}

	/** The icon's size in units. */
	public IconButton iconSize(float units) {
		this.iconSize = units;
		return this;
	}

	/** Without a hover surface: only the icon brightens (close buttons, row actions). */
	public IconButton bare() {
		this.surface = false;
		return this;
	}

	@Override
	public float prefWidth() {
		return w;
	}

	@Override
	public float prefHeight(float width) {
		return h;
	}

	@Override
	public String narration() {
		return withReason(name + ", button");
	}

	@Override
	protected void paint(Canvas c) {
		int x0 = px0(c);
		int y0 = py0(c);
		int pw = pxW(c);
		int ph = pxH(c);
		float lift = Math.max(hover, press);
		if (surface && (lift > 0.01F || active)) {
			int fill = active ? Tokens2.SELECTED : Palette.lerp(Tokens2.HOVER, Tokens2.PRESS, press);
			c.roundRect(x0, y0, pw, ph, c.px(Math.min(5, Math.min(w, h) / 2)), Palette.withAlpha(fill, active ? 1 : Math.max(lift, 0.01F)));
		}
		int ink = tint != 0 ? tint : active ? Tokens2.EMBER_TEXT : Palette.lerp(Tokens2.muted(), Tokens2.TEXT, Math.max(lift, focus));
		if (!enabled()) {
			ink = Palette.withAlpha(Tokens2.muted(), 0.4F);
		}
		int size = c.px(iconSize);
		c.icon(icon, x0 + (pw - size) / 2, y0 + (ph - size) / 2, size, ink);
		Kit.focusRing(c, x, y, w, h, Math.min(5, Math.min(w, h) / 2), focus);
	}
}
