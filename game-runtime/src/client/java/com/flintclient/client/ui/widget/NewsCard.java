package com.flintclient.client.ui.widget;

import com.flintclient.client.module.news.WhatsNew;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** The small "What's new" card: the bundled notes for this Flint version. Opens the full notes. */
public final class NewsCard extends FlintButton {
	public static final float PAD_X = 6.8F;

	private final TextLine heading;
	private final TextLine title;
	private final TextLine summary;
	private String fullTitle = "";
	private String fullSummary = "";
	private int fittedFor = -1;

	public NewsCard(WhatsNew.Note note, Runnable action) {
		super(Component.translatable("flintclient.news.open", note.title()), action);
		this.heading = new TextLine(FlintFonts.CAPS, Component.translatable("flintclient.news.heading").getString().toUpperCase(Locale.ROOT), 0.22F);
		this.fullTitle = note.title();
		this.fullSummary = note.summary().isEmpty() && !note.points().isEmpty() ? note.points().getFirst() : note.summary();
		this.title = new TextLine(FlintFonts.STRONG, fullTitle);
		this.summary = new TextLine(FlintFonts.SMALL, fullSummary);
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover * 0.7F);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
		c.panel(x, y, w, h, c.px(3.2F), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);

		int inner = w - 2 * c.px(PAD_X);
		if (fittedFor != w * 31 + c.scale()) {
			fittedFor = w * 31 + c.scale();
			fit(c, title, fullTitle, inner);
			fit(c, summary, fullSummary, inner);
		}
		int left = x + c.px(PAD_X);
		c.text(heading, left, y + c.px(8.6F), Palette.ACCENT_TEXT);
		c.text(title, left, y + c.px(16.6F), Palette.TEXT);
		c.text(summary, left, y + c.px(24.4F), Palette.lerp(Palette.MUTED, Palette.TEXT_DIM, hover));
	}

	/** Shortens text that doesn't fit with an ellipsis, so long titles never overflow the card. */
	private static void fit(Canvas c, TextLine line, String full, int maxPx) {
		line.set(full);
		if (c.width(line) <= maxPx) {
			return;
		}
		String text = full;
		while (text.length() > 1) {
			text = text.substring(0, text.length() - 1).stripTrailing();
			line.set(text + "…");
			if (c.width(line) <= maxPx) {
				return;
			}
		}
	}
}
