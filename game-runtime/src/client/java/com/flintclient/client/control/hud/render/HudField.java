package com.flintclient.client.control.hud.render;

import org.jspecify.annotations.Nullable;

/**
 * One number on a module's line: an optional text before it ("FPS", "FPS:", "1% LOW"), the value in tabular
 * digits (or an em dash while there is no data), and an optional text after it ("FPS", "ms"). The view chooses the
 * texts each frame ({@link #affixes}: a label position or a unit is a choice of texts, nothing is built), and a
 * text left empty in the current style is left out. The digits live in a reused buffer, rewritten only when the
 * value changes; a minimum number of digit cells keeps the width steady as the value changes length (60 to 144 FPS
 * doesn't resize the panel), with the spare cells on the side away from the text after it, so "60 FPS" never reads
 * "60  FPS". Measuring and drawing are the same walk, so they always agree. Render thread.
 */
final class HudField {
	static final HudText DASH = new HudText("—");

	static final int TEXT = 0;
	static final int MUTED = 1;

	final char[] digits = new char[HudFormat.CHARS];
	int length;
	boolean known;
	private long shown = Long.MIN_VALUE;
	private int shownDecimals = -1;
	private @Nullable HudText prefix;
	private @Nullable HudText suffix;
	private int minCells;
	/** The colour role of the value: {@link #TEXT} or {@link #MUTED}. */
	private final int tone;
	/** A colour for the value instead of its tone's (the ping's latency tint), or 0. */
	private int color;
	/** The text after the value follows it without a gap ("172°"). */
	private boolean tight;

	HudField(int minCells, int tone) {
		this.minCells = minCells;
		this.tone = tone;
	}

	/** The texts before and after the value (null: none). Allocation-free; returns this. */
	HudField affixes(@Nullable HudText before, @Nullable HudText after) {
		this.prefix = before;
		this.suffix = after;
		return this;
	}

	/** The text after the value follows it directly, as a degree sign does. Returns this. */
	HudField tight() {
		this.tight = true;
		return this;
	}

	/** The fewest digit cells the value takes. */
	HudField cells(int value) {
		this.minCells = value;
		return this;
	}

	/** Draws the value in {@code argb} instead of its tone's colour; 0 goes back to the tone. */
	void color(int argb) {
		this.color = argb;
	}

	/** Shows {@code value} (rewritten only when it changed). */
	void set(long value) {
		known = true;
		if (value != shown || shownDecimals != 0) {
			shown = value;
			shownDecimals = 0;
			length = HudFormat.integer(value, digits);
		}
	}

	/** Shows {@code value} rounded to {@code decimals} decimals ({@link HudFormat#fixed}), rewritten only when the shown text changes. */
	void setFixed(double value, int decimals) {
		long key = Math.round(Math.clamp(value, -99_999.0, 99_999.0) * (decimals == 0 ? 1 : decimals == 1 ? 10 : 100));
		known = true;
		if (key != shown || decimals != shownDecimals) {
			shown = key;
			shownDecimals = decimals;
			length = HudFormat.fixed(value, decimals, digits);
		}
	}

	/** No data: an em dash. */
	void clear() {
		known = false;
		shown = Long.MIN_VALUE;
		shownDecimals = -1;
		length = 0;
	}

	/** The value's own width: its digits, or the dash. */
	int valueWidth(HudPen p) {
		return known ? p.width(digits, length, HudFaces.VALUE) : p.width(DASH, HudFaces.VALUE);
	}

	/** The room the value takes: its own width, but at least {@link #cells} digit cells. */
	int slotWidth(HudPen p) {
		return Math.max(valueWidth(p), minCells * p.cell(HudFaces.VALUE));
	}

	/**
	 * Lays the field out from {@code x} on {@code baseline}: draws it when {@code draw}, else only measures. The
	 * value takes its slot ({@link #slotWidth}). Returns where it ends.
	 */
	int run(HudPen p, boolean draw, int x, int baseline) {
		return run(p, draw, x, baseline, false);
	}

	/**
	 * As {@link #run(HudPen, boolean, int, int)}; {@code packed} lays the value out at its own width, without spare
	 * cells (for lines that keep their width steady as a whole).
	 */
	int run(HudPen p, boolean draw, int x, int baseline, boolean packed) {
		HudStyle s = p.s;
		int at = x;
		if (shows(prefix, s)) {
			at = draw ? p.text(prefix, HudFaces.LABEL, at, baseline, s.label) : at + p.width(prefix, HudFaces.LABEL);
			at += s.space;
		}
		boolean unit = shows(suffix, s);
		int own = valueWidth(p);
		int valueWidth = packed ? own : Math.max(own, minCells * p.cell(HudFaces.VALUE));
		if (draw) {
			int left = unit ? at + valueWidth - own : at;
			if (known) {
				p.digits(digits, length, HudFaces.VALUE, left, baseline, color != 0 ? color : tone == MUTED ? s.muted : s.text);
			} else {
				p.text(DASH, HudFaces.VALUE, left, baseline, s.muted);
			}
		}
		at += valueWidth;
		if (unit) {
			at += tight ? 0 : s.thin;
			at = draw ? p.text(suffix, HudFaces.LABEL, at, baseline, s.label) : at + p.width(suffix, HudFaces.LABEL);
		}
		return at;
	}

	private static boolean shows(@Nullable HudText text, HudStyle s) {
		return text != null && !text.blank(s.theme);
	}
}
