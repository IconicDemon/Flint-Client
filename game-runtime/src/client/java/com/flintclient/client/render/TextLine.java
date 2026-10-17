package com.flintclient.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;

/**
 * A piece of text in one {@link FontFace} (a {@link FlintFonts} role or a {@link ScaledFont}), with its
 * styled components and pixel widths cached per scale, so drawing it allocates nothing. Optional
 * tracking (letter spacing, in em) is applied per character and rounded to whole pixels.
 *
 * <p>A line that has to fit a width ({@link Canvas#text(TextLine, int, int, int, int)}) keeps one
 * shortened copy ending in an ellipsis, rebuilt only when the text, scale or width changes.
 */
public final class TextLine {
	private static final String ELLIPSIS = "…";

	private final FontFace face;
	private final float trackingEm;
	private String text;
	private int scale = -1;
	private Component whole;
	private Component[] glyphs;
	private int[] offsets;
	private int widthPx;
	/** Text the bundled font can't show (for example CJK) is drawn in Minecraft's own font instead. */
	private boolean fallback;
	/** The shortened copy for {@link #fitMaxPx} at {@link #fitScale} (-1 when there is none). */
	private @Nullable TextLine fit;
	private int fitMaxPx = -1;
	private int fitScale = -1;

	public TextLine(FontFace face, String text) {
		this(face, text, 0.0F);
	}

	public TextLine(FontFace face, String text, float trackingEm) {
		this.face = face;
		this.text = text;
		this.trackingEm = trackingEm;
	}

	public FontFace face() {
		return face;
	}

	/** The face, under its older name. */
	public FontFace role() {
		return face;
	}

	public String text() {
		return text;
	}

	public void set(String text) {
		if (!text.equals(this.text)) {
			this.text = text;
			scale = -1;
			fitScale = -1;
		}
	}

	void prepare(int guiScale, Font font) {
		if (guiScale == scale) {
			return;
		}
		scale = guiScale;
		fallback = !face.covers(text);
		Style style = Style.EMPTY.withFont(fallback ? FontDescription.DEFAULT : face.forScale(guiScale));
		if (trackingEm == 0.0F) {
			whole = Component.literal(text).withStyle(style);
			widthPx = Math.round(font.getSplitter().stringWidth(whole) * guiScale);
			glyphs = null;
			offsets = null;
			return;
		}
		int tracking = Math.round(trackingEm * face.size() * guiScale);
		int[] codepoints = text.codePoints().toArray();
		glyphs = new Component[codepoints.length];
		offsets = new int[codepoints.length];
		int x = 0;
		for (int i = 0; i < codepoints.length; i++) {
			glyphs[i] = Component.literal(Character.toString(codepoints[i])).withStyle(style);
			offsets[i] = x;
			x += Math.round(font.getSplitter().stringWidth(glyphs[i]) * guiScale);
			if (i < codepoints.length - 1) {
				x += tracking;
			}
		}
		widthPx = x;
		whole = null;
	}

	/**
	 * This line, or a copy cut short with an ellipsis so it is at most {@code maxPx} wide (whole
	 * characters only, trailing spaces dropped). Built only when the text, scale or width changes; a width
	 * between the copy's own and the one it was built for gives the same copy, so measuring a line with
	 * {@code maxPx} and then drawing it at the width that returned reuses it.
	 */
	TextLine fitted(int guiScale, Font font, int maxPx) {
		prepare(guiScale, font);
		if (widthPx <= maxPx) {
			return this;
		}
		if (fit != null && fitScale == guiScale && reusesFit(fit.widthPx, fitMaxPx, maxPx)) {
			return fit;
		}
		if (fit == null) {
			fit = new TextLine(face, "", trackingEm);
		}
		fitScale = guiScale;
		fitMaxPx = maxPx;
		fit.set(ELLIPSIS);
		int[] ends = cutPoints(text);
		// Binary search for the longest prefix that still fits with the ellipsis.
		int lo = 0;
		int hi = ends.length - 1;
		String best = ELLIPSIS;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			String candidate = text.substring(0, ends[mid]).stripTrailing() + ELLIPSIS;
			fit.set(candidate);
			fit.prepare(guiScale, font);
			if (fit.widthPx <= maxPx) {
				best = candidate;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		fit.set(best);
		fit.prepare(guiScale, font);
		return fit;
	}

	/**
	 * Whether the copy built for {@code fitMaxPx} ({@code fitWidthPx} wide) is also the one for
	 * {@code maxPx}: the same width, or a narrower one it still fits in (every longer prefix was already
	 * too wide). The same width also covers a copy that is only the ellipsis and still too wide.
	 */
	static boolean reusesFit(int fitWidthPx, int fitMaxPx, int maxPx) {
		return maxPx == fitMaxPx || fitWidthPx <= maxPx && maxPx <= fitMaxPx;
	}

	/** The char index after each of the first n-1 code points (prefix ends, shortest first, never the whole text). */
	static int[] cutPoints(String text) {
		int count = text.codePointCount(0, text.length());
		int[] ends = new int[Math.max(0, count - 1) + 1];
		int index = 0;
		ends[0] = 0;
		for (int i = 1; i < ends.length; i++) {
			index += Character.charCount(text.codePointAt(index));
			ends[i] = index;
		}
		return ends;
	}

	/** Cap height in pixels for the font actually used. */
	public float capHeightPx(int guiScale) {
		return fallback ? 7.0F * guiScale : face.capHeightPx(guiScale);
	}

	/** Width in pixels at the GUI scale it was last prepared for. */
	public int widthPx() {
		return widthPx;
	}

	Component whole() {
		return whole;
	}

	Component[] glyphs() {
		return glyphs;
	}

	int[] offsets() {
		return offsets;
	}
}
