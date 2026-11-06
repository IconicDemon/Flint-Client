package com.flintclient.client.ui.screen;

import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Paragraph;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The full notes for this Flint version, from the same bundled file as the card: "Flint 1.0.2" (the version, as the card
 * names it), the release's title and date, then every point. Scrolls (wheel, arrow keys, Page Up/Down, Home/End) when the
 * notes are taller than the window, with a thin bar at the right, as the credits page does.
 */
public final class WhatsNewScreen extends FlintPageScreen {
	private static final int TEXT_W = 190;
	private static final float WHEEL_STEP = 24;

	private final TextLine version;
	private final TextLine title;
	private final TextLine dated;
	private final List<Paragraph> points = new ArrayList<>();
	private int top;
	/** Where the notes are scrolled to, and where they are drawn (eased towards it), in menu units. */
	private float scroll;
	private float shown;
	private int contentPx;
	private int viewPx;

	public WhatsNewScreen(Screen parent, WhatsNew.Note note) {
		super(parent, Component.translatable("flintclient.news.heading"));
		this.version = new TextLine(FlintFonts.HEADING, "Flint " + note.version());
		this.title = new TextLine(FlintFonts.STRONG, note.title());
		this.dated = new TextLine(FlintFonts.SMALL, Component.translatable("flintclient.news.version", note.version(), note.date()).getString());
		for (String point : note.points()) {
			points.add(new Paragraph(FlintFonts.LABEL, point));
		}
	}

	@Override
	protected void initPage(int contentTop) {
		top = contentTop + 6;
		scroll = Math.clamp(scroll, 0, maxScroll());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scrollBy((float) -scrollY * WHEEL_STEP);
		return true;
	}

	private void scrollBy(float units) {
		scroll = Math.clamp(scroll + units, 0, maxScroll());
	}

	/** How far the notes scroll, in menu units. */
	private float maxScroll() {
		float px = Math.max(1.0F, com.flintclient.client.ui.MenuScale.pixels());
		return Math.max(0, (contentPx - viewPx) / (float) px);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int key = event.key();
		float page = Math.max(20, height - top - MARGIN - 20);
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN) {
			scrollBy(WHEEL_STEP);
		} else if (key == com.mojang.blaze3d.platform.InputConstants.KEY_UP) {
			scrollBy(-WHEEL_STEP);
		} else if (key == com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN) {
			scrollBy(page);
		} else if (key == com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP) {
			scrollBy(-page);
		} else if (key == com.mojang.blaze3d.platform.InputConstants.KEY_HOME) {
			scroll = 0;
		} else if (key == com.mojang.blaze3d.platform.InputConstants.KEY_END) {
			scroll = maxScroll();
		} else {
			return super.keyPressed(event);
		}
		return true;
	}

	@Override
	protected void paintPage(Canvas c) {
		shown = Motion.reduced() ? scroll : Motion.approach(shown, scroll, 0.06F);
		int w = c.px(Math.min(TEXT_W, width - 2 * MARGIN));
		int x = (c.px(width) - w) / 2;
		int viewTop = c.px(top);
		int viewBottom = c.px(height - 6);
		viewPx = viewBottom - viewTop;
		c.pushClip(0, viewTop, c.px(width), viewBottom);
		int start = viewTop + c.px(4);
		int y = start - Math.round(shown * c.unitScale());
		try {
			c.text(version, x, y + c.px(6), Palette.TEXT);
			y += c.px(18);
			c.text(title, x, y, Palette.ACCENT_TEXT);
			y += c.px(8);
			c.text(dated, x, y, Palette.MUTED);
			y += c.px(13);
			int bullet = c.px(1.6F);
			int indent = c.px(6);
			for (Paragraph point : points) {
				int line = point.lineHeightPx(c);
				boolean first = true;
				for (TextLine text : point.lines(c, w - indent)) {
					if (first) {
						int capCenter = y - Math.round(text.capHeightPx(c.unitScale()) / 2.0F);
						c.roundRect(x + c.px(1), capCenter - bullet / 2, bullet, bullet, bullet / 2, Palette.ACCENT);
						first = false;
					}
					c.text(text, x + indent, y, Palette.TEXT_DIM);
					// A line in Minecraft's font (a language name in its own script) can stand taller than the face's: it never
					// runs into the next.
					y += text.fallback() ? Math.max(line, Math.round(text.capHeightPx(c.unitScale()) * 1.6F)) : line;
				}
				y += c.px(3);
			}
			y += c.px(10);
		} finally {
			c.popClip();
		}
		contentPx = y + Math.round(shown * c.unitScale()) - start;
		paintBar(c, viewTop, viewBottom);
	}

	/** A thin bar at the right while there is more to scroll. */
	private void paintBar(Canvas c, int viewTop, int viewBottom) {
		int view = viewBottom - viewTop;
		if (contentPx <= view || contentPx <= 0) {
			return;
		}
		int barH = Math.max(c.px(12), view * view / contentPx);
		float max = maxScroll();
		float k = max <= 0 ? 0 : Math.clamp(shown / max, 0, 1);
		int barY = viewTop + Math.round((view - barH) * k);
		int barW = Math.max(2, c.px(1.2F));
		int barX = c.px(width) - c.px(MARGIN / 2.0F) - barW;
		c.roundRect(barX, barY, barW, barH, barW / 2, 0x40FFFFFF);
	}
}
