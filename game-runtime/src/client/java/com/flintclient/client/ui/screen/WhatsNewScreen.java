package com.flintclient.client.ui.screen;

import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Paragraph;
import com.flintclient.client.render.TextLine;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The full notes for this Flint version, from the same bundled file as the card. */
public final class WhatsNewScreen extends FlintPageScreen {
	private static final int TEXT_W = 190;

	private final TextLine version;
	private final TextLine title;
	private final List<Paragraph> points = new ArrayList<>();
	private int top;

	public WhatsNewScreen(Screen parent, WhatsNew.Note note) {
		super(parent, Component.translatable("flintclient.news.heading"));
		this.version = new TextLine(FlintFonts.SMALL, Component.translatable("flintclient.news.version", note.version(), note.date()).getString());
		this.title = new TextLine(FlintFonts.HEADING, note.title());
		for (String point : note.points()) {
			points.add(new Paragraph(FlintFonts.LABEL, point));
		}
	}

	@Override
	protected void initPage(int contentTop) {
		top = contentTop + 6;
	}

	@Override
	protected void paintPage(Canvas c) {
		int w = c.px(Math.min(TEXT_W, width - 2 * MARGIN));
		int x = (c.px(width) - w) / 2;
		int y = c.px(top);
		c.text(version, x, y, Palette.ACCENT_TEXT);
		y += c.px(12);
		c.text(title, x, y, Palette.TEXT);
		y += c.px(12);
		int bullet = c.px(1.6F);
		int indent = c.px(6);
		for (Paragraph point : points) {
			int line = point.lineHeightPx(c);
			boolean first = true;
			for (TextLine text : point.lines(c, w - indent)) {
				if (first) {
					int capCenter = y - Math.round(text.capHeightPx(c.scale()) / 2.0F);
					c.roundRect(x + c.px(1), capCenter - bullet / 2, bullet, bullet, bullet / 2, Palette.ACCENT);
					first = false;
				}
				c.text(text, x + indent, y, Palette.TEXT_DIM);
				y += line;
			}
			y += c.px(3);
		}
	}
}
