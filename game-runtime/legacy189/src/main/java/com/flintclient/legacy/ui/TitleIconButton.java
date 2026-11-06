package com.flintclient.legacy.ui;

import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * A square icon button on Minecraft 1.8.9's title screen, as its language button is: Minecraft's own button, 20 × 20, with a
 * Font Awesome icon in the colours (and the shadow) Minecraft gives a button's label, and an optional count in Flint's
 * orange at its top right corner (nothing at zero). Its message is its name (the title screen shows it as its tooltip; QA
 * finds the button by it); the button draws no text. Render thread.
 */
public final class TitleIconButton extends ButtonWidget {
	public static final int SIZE = 20;
	/** Font Awesome 6 Free Solid: users, message. */
	public static final int USERS = 0xF0C0;
	public static final int MESSAGE = 0xF27A;
	/** The icon's height in GUI units. */
	private static final float ICON = 10;

	private final int glyph;
	private final String tooltip;
	private final IntSupplier badge;
	private TextLine badgeText = new TextLine(PixelFace.fixed(1), "");
	private int badgeScale = 1;
	private int badgeCount;

	public TitleIconButton(int id, int x, int y, String name, String tooltip, int glyph, IntSupplier badge) {
		super(id, x, y, SIZE, SIZE, name);
		this.glyph = glyph;
		this.tooltip = tooltip;
		this.badge = badge;
	}

	/** What hovering it says. */
	public String tooltip() {
		return tooltip;
	}

	@Override
	public void render(Minecraft mc, int mouseX, int mouseY) {
		if (!visible) {
			return;
		}
		// Minecraft's own button (its hover state too), without a label.
		String name = message;
		message = "";
		try {
			super.render(mc, mouseX, mouseY);
		} finally {
			message = name;
		}
		int count = active ? badge.getAsInt() : 0;
		Canvas c = Canvas.begin(Frame.get());
		try {
			// A label's colours: grey when it can't be pressed, yellow under the pointer, else light grey; its shadow a quarter as bright.
			int ink = !active ? 0xA0A0A0 : hovered ? 0xFFFFA0 : 0xE0E0E0;
			int shadow = (ink & 0xFCFCFC) >> 2;
			int size = c.px(ICON);
			int left = c.px(x + SIZE / 2.0F) - size / 2;
			int top = c.px(y + SIZE / 2.0F) - size / 2;
			int drop = c.guiScale();
			c.glyph(glyph, left + drop, top + drop, size, 0xFF000000 | shadow);
			c.glyph(glyph, left, top, size, 0xFF000000 | ink);
			if (count > 0) {
				paintBadge(c, count);
			}
		} finally {
			c.end();
		}
	}

	/** The count on a small orange pill over the top right corner, its digits in Minecraft's font at whole pixels. */
	private void paintBadge(Canvas c, int count) {
		// About a third of the button tall at every GUI scale (whole font pixels: 1 at scale 2, 2 at 3, 3 at 4).
		int scale = Math.max(1, c.guiScale() - 1);
		if (scale != badgeScale) {
			badgeScale = scale;
			badgeText = new TextLine(PixelFace.fixed(scale), "");
			badgeCount = 0;
		}
		if (count != badgeCount) {
			badgeCount = count;
			badgeText.set(count > 9 ? "9+" : Integer.toString(count));
		}
		int pad = scale + 1;
		int h = 7 * scale + 2 * pad;
		int w = Math.max(h, c.width(badgeText) + 2 * (pad + scale));
		int right = c.px(x + SIZE) + c.px(2);
		int top = c.px(y) - c.px(2);
		c.roundRect(right - w, top, w, h, h / 2, Palette.ACCENT);
		c.text(badgeText, right - w + (w - c.width(badgeText)) / 2, c.baselineFor(badgeText, top + h / 2), Palette.ACCENT_INK);
	}
}
