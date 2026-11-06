package com.flintclient.client.ui.screen;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Paragraph;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.widget.MenuButton;
import com.flintclient.client.util.FlintCredits;
import com.flintclient.client.util.FlintInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Flint's credits (Utilities › Credits): Flint Client, its version and its maker; its licence; the open-source software it
 * runs on, its fonts and icons, and the mods its features adapt, each with its licence ({@link FlintCredits}); and that Flint
 * isn't an official Minecraft product. Minecraft's own Credits and Attribution screen is one button away (top right). The
 * page scrolls with the wheel (or the arrow keys, Page Up and Down, Home and End), with a thin bar at the right.
 */
public final class FlintCreditsScreen extends FlintPageScreen {
	private static final int TEXT_W = 236;
	private static final float WHEEL_STEP = 24;

	private final TextLine mark = new TextLine(FlintFonts.WORDMARK, "FLINT", 0.34F);
	private final TextLine product = new TextLine(FlintFonts.HEADING, FlintCredits.PRODUCT);
	private final TextLine version = new TextLine(FlintFonts.SMALL, "");
	private final TextLine creator = new TextLine(FlintFonts.STRONG, FlintCredits.CREATOR);
	private final Paragraph license = new Paragraph(FlintFonts.LABEL, FlintCredits.LICENSE);
	private final Paragraph notOfficial = new Paragraph(FlintFonts.STRONG, FlintCredits.NOT_OFFICIAL);
	private final Paragraph minecraftNote = new Paragraph(FlintFonts.LABEL, FlintCredits.MINECRAFT);
	private final List<Block> blocks = new ArrayList<>();
	private int top;
	private float scroll;
	private float shown;
	/** The content's height in pixels at the last draw (the scroll's limit). */
	private int contentPx;
	private int viewPx;

	/** A section title, or one credited work: name and licence on a line, who made it below. */
	private record Block(TextLine title, TextLine name, TextLine license, TextLine by) {
	}

	public FlintCreditsScreen(Screen parent) {
		super(parent, Component.translatable("flintclient.credits.title"));
		version.set("Version " + FlintInfo.flintVersion() + " · Minecraft " + FlintInfo.minecraftVersion());
		for (FlintCredits.Section section : FlintCredits.SECTIONS) {
			blocks.add(new Block(new TextLine(FlintFonts.CAPS, section.title().toUpperCase(java.util.Locale.ROOT), 0.16F), null, null, null));
			for (FlintCredits.Entry entry : section.entries()) {
				blocks.add(new Block(null, new TextLine(FlintFonts.STRONG, entry.name()), new TextLine(FlintFonts.SMALL, entry.license()),
					entry.by() == null ? null : new TextLine(FlintFonts.SMALL, entry.by())));
			}
		}
	}

	@Override
	protected void initPage(int contentTop) {
		top = contentTop;
		MenuButton minecraftCredits = addRenderableWidget(new MenuButton(Component.translatable("flintclient.credits.minecraft"), Icon.SPARK,
			MenuButton.Style.NEUTRAL, () -> minecraft.gui.setScreen(new CreditsAndAttributionScreen(this))));
		int bw = Math.min(140, width - 2 * MARGIN - 80);
		minecraftCredits.setRectangle(bw, HEADER_H, width - MARGIN - bw, MARGIN);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scrollBy((float) -scrollY * WHEEL_STEP);
		return true;
	}

	private void scrollBy(float units) {
		scroll = Math.clamp(scroll + units, 0, maxScroll());
	}

	/** How far the content scrolls, in menu units. */
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
			y = paintHeader(c, x, w, y);
			for (Block block : blocks) {
				if (block.title() != null) {
					y += c.px(10);
					c.text(block.title(), x, y, Palette.ACCENT_TEXT);
					y += c.px(3);
					c.fill(x, y, x + w, y + Math.max(1, c.px(0.25F)), 0x22FFFFFF);
					y += c.px(8);
					continue;
				}
				c.text(block.name(), x, y, Palette.TEXT);
				c.textRight(block.license(), x + w, y, Palette.MUTED);
				y += c.px(6.5F);
				if (block.by() != null) {
					c.text(block.by(), x, y, Palette.FAINT, w);
					y += c.px(6.5F);
				}
				y += c.px(2.5F);
			}
			y += c.px(10);
			c.fill(x, y - c.px(6), x + w, y - c.px(6) + Math.max(1, c.px(0.25F)), 0x22FFFFFF);
			y = paragraph(c, notOfficial, x, w, y, Palette.TEXT);
			y += c.px(3);
			y = paragraph(c, minecraftNote, x, w, y, Palette.MUTED);
			y += c.px(14);
		} finally {
			c.popClip();
		}
		contentPx = y + Math.round(shown * c.unitScale()) - start;
		paintBar(c, viewTop, viewBottom);
	}

	/** Flint's mark and name, the version, the maker and the licence. */
	private int paintHeader(Canvas c, int x, int w, int y) {
		int markH = c.px(13);
		int markW = MaskAtlas.markWidth(markH);
		int gap = c.px(4);
		int wordW = c.width(mark);
		int left = x + (w - (markW + gap + wordW)) / 2;
		c.mark(left, y, markH, 1.0F);
		c.text(mark, left + markW + gap, c.baselineFor(mark, y + markH / 2), Palette.TEXT);
		y += markH + c.px(11);
		c.textCentered(product, x + w / 2, y, Palette.TEXT);
		y += c.px(8);
		c.textCentered(version, x + w / 2, y, Palette.MUTED);
		y += c.px(10);
		c.textCentered(creator, x + w / 2, y, Palette.ACCENT_TEXT);
		y += c.px(12);
		return paragraph(c, license, x, w, y, Palette.TEXT_DIM);
	}

	private static int paragraph(Canvas c, Paragraph text, int x, int w, int y, int ink) {
		int line = text.lineHeightPx(c);
		for (TextLine l : text.lines(c, w)) {
			c.text(l, x, y, ink);
			y += line;
		}
		return y;
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
