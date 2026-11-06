package com.flintclient.client.render;

import com.flintclient.client.platform.FontRef;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.TextEngine;
import com.flintclient.client.platform.TextRun;

/**
 * Numbers that change every frame (FPS, coordinates, timers) drawn with tabular figures: each digit
 * sits centred in a cell as wide as the face's widest digit, so values don't jitter as they change
 * (Inter's own digits are proportional: a "1" is far narrower than a "4"). See {@link DigitCells} for the
 * characters it shows and their cells.
 *
 * <p>Each character is a {@link Component} built once per pixel scale, and drawing takes a
 * {@link CharSequence} or a reused {@code char[]}, so a changing value allocates nothing of Flint's own.
 * One run per face can be shared by everything drawing that face. Render thread.
 */
public final class DigitRun {
	/** Pixel scales with their own cached glyphs; anything larger (beyond any real window) shares the last. */
	private static final int MAX_CACHED_SCALE = 32;

	private final FontFace face;
	/** Per pixel scale (index), built the first time it is drawn at that scale. */
	private final TextRun[][] glyphs = new TextRun[MAX_CACHED_SCALE + 1][];
	private final int[][] widths = new int[MAX_CACHED_SCALE + 1][];
	private final int[][] offsets = new int[MAX_CACHED_SCALE + 1][];

	public DigitRun(FontFace face) {
		this.face = face;
	}

	public FontFace face() {
		return face;
	}

	/** True when a run can show {@code c} (digits, {@code . - : / %}, space and U+2212 minus). */
	public static boolean supports(char c) {
		return DigitCells.slot(c) >= 0;
	}

	/** The digit cell's width in pixels at the canvas's scale. */
	public int cellPx(Canvas c) {
		return widths(c)[0];
	}

	public int width(Canvas c, CharSequence text) {
		return DigitCells.width(widths(c), text);
	}

	public int width(Canvas c, char[] text, int length) {
		return DigitCells.width(widths(c), text, length);
	}

	/** Draws {@code text} from {@code x} with its baseline at {@code baselinePx}; returns where it ends. */
	public int draw(Canvas c, CharSequence text, int x, int baselinePx, int argb) {
		int index = prepare(c);
		argb = c.tint(argb);
		if ((argb >>> 24) == 0) {
			return x + width(c, text);
		}
		int s = c.scale();
		Frame frame = c.frame();
		float top = (float) baselinePx / s - FlintFonts.BASELINE;
		for (int i = 0; i < text.length(); i++) {
			x = glyph(frame, DigitCells.slot(text.charAt(i)), index, s, x, top, argb);
		}
		return x;
	}

	/** {@link #draw(Canvas, CharSequence, int, int, int)} for the first {@code length} characters of a buffer. */
	public int draw(Canvas c, char[] text, int length, int x, int baselinePx, int argb) {
		int index = prepare(c);
		argb = c.tint(argb);
		if ((argb >>> 24) == 0) {
			return x + width(c, text, length);
		}
		int s = c.scale();
		Frame frame = c.frame();
		float top = (float) baselinePx / s - FlintFonts.BASELINE;
		for (int i = 0; i < length; i++) {
			x = glyph(frame, DigitCells.slot(text[i]), index, s, x, top, argb);
		}
		return x;
	}

	/** Draws {@code text} ending at {@code rightPx} (right-aligned numbers keep their last digit still). */
	public void drawRight(Canvas c, CharSequence text, int rightPx, int baselinePx, int argb) {
		draw(c, text, rightPx - width(c, text), baselinePx, argb);
	}

	private int glyph(Frame frame, int slot, int index, int s, int x, float top, int argb) {
		if (slot < 0) {
			return x;
		}
		TextRun glyph = glyphs[index][slot];
		if (glyph != null) {
			frame.text(glyph, (float) (x + offsets[index][slot]) / s, top, s, argb);
		}
		return x + widths[index][slot];
	}

	private int[] widths(Canvas c) {
		return widths[prepare(c)];
	}

	/** Builds the glyphs and cells for the canvas's scale the first time; returns the cache index. */
	private int prepare(Canvas c) {
		int s = Math.max(1, c.scale());
		int index = Math.min(s, MAX_CACHED_SCALE);
		if (glyphs[index] != null) {
			return index;
		}
		TextEngine engine = c.text();
		FontRef font = face.forScale(s);
		TextRun[] built = new TextRun[DigitCells.SLOTS];
		int[] advances = new int[DigitCells.SLOTS];
		for (int slot = 0; slot < DigitCells.SLOTS; slot++) {
			char ch = DigitCells.CHARS.charAt(slot);
			// The space draws nothing; only its cell matters.
			built[slot] = ch == ' ' ? null : engine.run(String.valueOf(ch), font);
			advances[slot] = Math.round(engine.width(engine.run(String.valueOf(ch), font)) * s);
		}
		int[] w = new int[DigitCells.SLOTS];
		int[] o = new int[DigitCells.SLOTS];
		DigitCells.layout(advances, w, o);
		widths[index] = w;
		offsets[index] = o;
		glyphs[index] = built;
		return index;
	}
}
