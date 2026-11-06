package com.flintclient.client.render;

import com.flintclient.client.platform.FontRef;
import com.flintclient.client.platform.TextEngine;
import com.flintclient.client.platform.TextRun;
import org.jspecify.annotations.Nullable;

/**
 * A piece of text in one {@link FontFace} (a {@link FlintFonts} role or a {@link ScaledFont}), with its
 * styled components and pixel widths cached per scale, so drawing it allocates nothing. Optional
 * tracking (letter spacing, in em) is applied per character and rounded to whole pixels.
 *
 * <p>A scale is pixels per unit, usually a whole number. At a fractional one (Flint's main menu, sized from the window) the
 * face is drawn as the same typeface at its size in pixels, rasterised for exactly that ({@link #atScale}), so it is as crisp
 * as at a whole scale.
 *
 * <p>A line that has to fit a width ({@link Canvas#text(TextLine, int, int, int, int)}) keeps one
 * shortened copy ending in an ellipsis, rebuilt only when the text, scale or width changes.
 */
public final class TextLine {
	private static final String ELLIPSIS = "…";
	/** Cap height of Minecraft's own font, in its pixels. */
	static final float MC_CAP_UNITS = 7.0F;

	private final FontFace face;
	private final float trackingEm;
	private String text;
	private int scale = -1;
	private TextRun whole;
	private TextRun[] glyphs;
	private int[] offsets;
	private int widthPx;
	/** Screen pixels per font unit as drawn ({@link FontFace#drawScale}). */
	private int drawScale;
	/** Cap height in pixels as last prepared. */
	private float capPx;
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

	/** Whether {@code scale} is a whole number of pixels per unit (as good as: the main menu's scales are tenth steps). */
	static boolean whole(float scale) {
		return Math.abs(scale - Math.round(scale)) < 0.001F;
	}

	/**
	 * The cache key for a scale: Minecraft's font in place of this face's is cached apart from it, and so is a fractional
	 * scale (in hundredths).
	 */
	private static int key(float guiScale) {
		int mode = PixelFace.everywhere();
		int base = whole(guiScale) ? Math.round(guiScale) : 10_000 + Math.round(guiScale * 100.0F);
		return mode == PixelFace.OFF ? base : base + 100_000 * (mode + 1);
	}

	/**
	 * The face that draws {@code shown} at the fractional scale {@code scale} when drawn at one pixel per unit: the same
	 * typeface at its size in pixels (a {@link ScaledFont}, rasterised for exactly that size, or Minecraft's font for that
	 * size); null for a face that has no such form (it is then drawn at the nearest whole scale).
	 */
	static @Nullable FontFace atScale(FontFace shown, float scale) {
		if (shown instanceof FlintFonts role) {
			return ScaledFont.exact(role, role.size * scale);
		}
		if (shown instanceof ScaledFont scaled) {
			return scaled.resized(scaled.size() * scale);
		}
		if (shown instanceof PixelFace pixels) {
			return pixels.fixedScale() > 0 ? pixels : PixelFace.of(pixels.size() * scale);
		}
		return null;
	}

	void prepare(int guiScale, TextEngine engine) {
		prepare((float) guiScale, engine);
	}

	void prepare(float guiScale, TextEngine engine) {
		int key = key(guiScale);
		if (key == scale) {
			return;
		}
		scale = key;
		FontFace face = PixelFace.shown(this.face);
		int at = Math.max(1, Math.round(guiScale));
		if (!whole(guiScale)) {
			FontFace exact = atScale(face, guiScale);
			if (exact != null) {
				face = exact;
				at = 1;
			}
		}
		fallback = !face.covers(text);
		// Minecraft's font is a bitmap: a whole number of pixels per font pixel, the one whose capitals (7 font pixels) come
		// nearest this face's. The GUI scale itself drew them at Minecraft's own size, over twice the height of Flint's body text
		// (the 0.9.1 notes' list of languages ran over the lines below it).
		drawScale = fallback ? fallbackScale(face.capHeightPx(at)) : face.drawScale(at);
		capPx = fallback ? MC_CAP_UNITS * drawScale : face.capHeightPx(at);
		FontRef font = fallback ? FontRef.MINECRAFT : face.forScale(at);
		if (trackingEm == 0.0F) {
			whole = engine.run(text, font);
			widthPx = Math.round(engine.width(whole) * drawScale);
			glyphs = null;
			offsets = null;
			return;
		}
		int tracking = Math.round(trackingEm * face.size() * at);
		int[] codepoints = text.codePoints().toArray();
		glyphs = new TextRun[codepoints.length];
		offsets = new int[codepoints.length];
		int x = 0;
		for (int i = 0; i < codepoints.length; i++) {
			glyphs[i] = engine.run(Character.toString(codepoints[i]), font);
			offsets[i] = x;
			x += Math.round(engine.width(glyphs[i]) * drawScale);
			if (i < codepoints.length - 1) {
				x += tracking;
			}
		}
		widthPx = x;
		whole = null;
	}

	/**
	 * Screen pixels per font pixel for text drawn in Minecraft's font in place of a face whose capitals are {@code faceCapPx}
	 * high: the whole number that brings Minecraft's capitals nearest that height, at least 1.
	 */
	static int fallbackScale(float faceCapPx) {
		return Math.max(1, Math.round(faceCapPx / MC_CAP_UNITS));
	}

	/** Whether this line was last prepared in Minecraft's font because the face lacks some of its characters. */
	public boolean fallback() {
		return fallback;
	}

	TextLine fitted(int guiScale, TextEngine engine, int maxPx) {
		return fitted((float) guiScale, engine, maxPx);
	}

	/**
	 * This line, or a copy cut short with an ellipsis so it is at most {@code maxPx} wide (whole
	 * characters only, trailing spaces dropped). Built only when the text, scale or width changes; a width
	 * between the copy's own and the one it was built for gives the same copy, so measuring a line with
	 * {@code maxPx} and then drawing it at the width that returned reuses it.
	 */
	TextLine fitted(float guiScale, TextEngine engine, int maxPx) {
		prepare(guiScale, engine);
		if (widthPx <= maxPx) {
			return this;
		}
		if (fit != null && fitScale == key(guiScale) && reusesFit(fit.widthPx, fitMaxPx, maxPx)) {
			return fit;
		}
		if (fit == null) {
			fit = new TextLine(face, "", trackingEm);
		}
		fitScale = key(guiScale);
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
			fit.prepare(guiScale, engine);
			if (fit.widthPx <= maxPx) {
				best = candidate;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		fit.set(best);
		fit.prepare(guiScale, engine);
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
		return capHeightPx((float) guiScale);
	}

	/** Cap height in pixels for the font actually used, at a scale that may be fractional. */
	public float capHeightPx(float guiScale) {
		if (key(guiScale) == scale) {
			return capPx;
		}
		return capHeightPx(face, guiScale);
	}

	/** Cap height in pixels of {@code face} (as shown now: {@link PixelFace#shown}) at a scale that may be fractional. */
	static float capHeightPx(FontFace face, float guiScale) {
		FontFace shown = PixelFace.shown(face);
		if (!whole(guiScale)) {
			FontFace exact = atScale(shown, guiScale);
			if (exact != null) {
				return exact.capHeightPx(1);
			}
		}
		return shown.capHeightPx(Math.max(1, Math.round(guiScale)));
	}

	/** Screen pixels per font unit, at the GUI scale it was last prepared for. */
	int drawScale() {
		return drawScale;
	}

	/** Width in pixels at the GUI scale it was last prepared for. */
	public int widthPx() {
		return widthPx;
	}

	TextRun whole() {
		return whole;
	}

	TextRun[] glyphs() {
		return glyphs;
	}

	int[] offsets() {
		return offsets;
	}
}
