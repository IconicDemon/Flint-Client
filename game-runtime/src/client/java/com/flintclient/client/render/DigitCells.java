package com.flintclient.client.render;

/**
 * The layout behind {@link DigitRun}, kept free of Minecraft so it can be tested: which characters a run
 * can show, how wide each one's cell is, and where its glyph sits in it.
 *
 * <p>Digits, both minus signs and the space share one cell width: the widest digit's advance. Any digit
 * can then replace any other (or a sign or blank) without moving the rest. The separators ({@code . :
 * / %}) keep their own advance: they sit at fixed places in a value's format, so a run's width still
 * depends only on its pattern and never on the digits it shows, and a full-width cell would leave
 * {@code 12 . 5} gaps around a narrow point.
 */
final class DigitCells {
	/** Every character a run can draw, in slot order. */
	static final String CHARS = "0123456789-− .:/%";
	static final int SLOTS = CHARS.length();
	/** Slots below this share the digit cell. */
	private static final int CELL_SLOTS = 13;

	private DigitCells() {
	}

	/** The slot for {@code c}, or -1 when a run can't show it. */
	static int slot(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		return switch (c) {
			case '-' -> 10;
			case '−' -> 11;
			case ' ' -> 12;
			case '.' -> 13;
			case ':' -> 14;
			case '/' -> 15;
			case '%' -> 16;
			default -> -1;
		};
	}

	static boolean sharesDigitCell(int slot) {
		return slot < CELL_SLOTS;
	}

	/**
	 * From each slot's own advance in pixels, fills each slot's cell width and the offset that centres its
	 * glyph in the cell (separators: their own width, offset 0).
	 */
	static void layout(int[] advances, int[] widths, int[] offsets) {
		int cell = 0;
		for (int digit = 0; digit <= 9; digit++) {
			cell = Math.max(cell, advances[digit]);
		}
		for (int slot = 0; slot < SLOTS; slot++) {
			if (sharesDigitCell(slot)) {
				widths[slot] = cell;
				offsets[slot] = (cell - advances[slot]) / 2;
			} else {
				widths[slot] = advances[slot];
				offsets[slot] = 0;
			}
		}
	}

	/** The width of a run: the sum of its cells (characters a run can't show take no space). */
	static int width(int[] widths, CharSequence text) {
		int total = 0;
		for (int i = 0; i < text.length(); i++) {
			int slot = slot(text.charAt(i));
			if (slot >= 0) {
				total += widths[slot];
			}
		}
		return total;
	}

	/** {@link #width(int[], CharSequence)} for the first {@code length} characters of a buffer. */
	static int width(int[] widths, char[] text, int length) {
		int total = 0;
		for (int i = 0; i < length; i++) {
			int slot = slot(text[i]);
			if (slot >= 0) {
				total += widths[slot];
			}
		}
		return total;
	}
}
