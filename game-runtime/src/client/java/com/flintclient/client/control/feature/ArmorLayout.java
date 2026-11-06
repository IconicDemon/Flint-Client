package com.flintclient.client.control.feature;

import com.flintclient.client.control.hud.HudPlacement;

/**
 * The geometry of Armor's strip, free of Minecraft so every line lays it out the same way: the strip of slots (uku's Armor
 * HUD's, 22 units thick with a slot every 20), and beside each slot its durability text and warning sign, above or below a
 * horizontal strip and left or right of a vertical one. The whole of it is the HUD's box: what the HUD editor outlines and
 * drags, and what Free placement keeps on screen.
 *
 * <p>The text has its own size and stays lined up with its slot: every slot's text on one line over or under a horizontal
 * strip, or in one column beside a vertical one, centred on its slot, so they all move together. At 100 % every position is
 * uku's. Over a horizontal strip, texts wider than a slot (bold, bigger sizes, four-digit numbers) would run into each other:
 * then all of them shrink by the same factor until the widest fits ({@link #FIT}), one size and one line. The room kept for text
 * is the widest the slots can show (see {@link ArmorPieces#widest}), so the box doesn't twitch as the numbers fall. One per
 * surface, reused; render thread.
 */
public final class ArmorLayout {
	/** A slot every 20 units; a slot's frame is 22. */
	public static final int STEP = 20;
	public static final int SIZE = 22;
	/** The durability text's line at full size, as uku's Armor HUD spaces it. */
	public static final int LINE = 9;
	/** The room a warning sign takes beside a slot. */
	public static final int SIGN = 10;
	/** The widest a slot's text may be over a horizontal strip: the slot pitch less a two-unit gap to its neighbour's. */
	public static final int FIT = STEP - 2;

	public int count;
	public boolean vertical;
	/** Whether the slots' durability shows as text, the warning signs show, and how much the signs bob. */
	public boolean text;
	public boolean warning;
	public int bob;
	/** The text size asked for ({@code 0.5} to {@code 2}), and the one drawn: smaller only to fit a horizontal strip's slots. */
	public float chosenScale = 1;
	public float textScale = 1;
	/** The text's line and the widest text, at the text's size (GUI units). */
	public int lineHeight;
	public int textWidth;
	/** The strip of slots; the whole box (strip, text and signs). */
	public int stripWidth;
	public int stripHeight;
	public int boxWidth;
	public int boxHeight;
	/** The text and signs' side: under a horizontal strip (else over it), right of a vertical one (else left). */
	public boolean after;
	/** The strip's top-left corner inside the box. */
	public int stripX;
	public int stripY;

	/** Free placement: the box's top-left corner and its size on screen (GUI units, on whole pixels), and its size step. */
	public float left;
	public float top;
	public float drawnWidth;
	public float drawnHeight;
	public float scale = 1;

	/**
	 * Lays out {@code count} slots. {@code widest} is the widest durability text at full size (GUI units, 0 when none shows),
	 * {@code textPercent} the text's size in percent. Call {@link #side} after.
	 */
	public void measure(int count, boolean vertical, boolean text, int widest, int textPercent, boolean warning, int bob) {
		this.count = count;
		this.vertical = vertical;
		this.text = text;
		this.warning = warning;
		this.bob = bob;
		chosenScale = Math.clamp(textPercent, 50, 200) / 100.0F;
		textScale = chosenScale;
		if (text && !vertical && count > 1 && widest > 0 && Math.round(widest * chosenScale) > FIT) {
			// Wider than a slot over a row of slots: every text shrinks alike until the widest fits.
			textScale = FIT / (float) widest;
		}
		lineHeight = scaled(LINE);
		textWidth = text ? scaled(widest) : 0;
		int length = count <= 0 ? 0 : SIZE + (count - 1) * STEP;
		stripWidth = vertical ? SIZE : length;
		stripHeight = vertical ? length : SIZE;
		if (vertical) {
			boxWidth = SIZE + besideWidth();
			boxHeight = stripHeight;
		} else {
			boxWidth = stripWidth + 2 * overhang();
			boxHeight = SIZE + aboveHeight();
		}
		side(after);
	}

	/** Puts the text and signs under (right of) the strip when {@code after}, else over (left of) it. */
	public void side(boolean after) {
		this.after = after;
		if (vertical) {
			stripX = after ? 0 : besideWidth();
			stripY = 0;
		} else {
			stripX = overhang();
			stripY = after ? 0 : aboveHeight();
		}
	}

	/** {@code width} units of full-size text at the text's size. */
	public int scaled(int width) {
		return Math.round(width * textScale);
	}

	/** Over or under a horizontal strip: the line of text and the warning signs (uku's Armor HUD's room for them). */
	private int aboveHeight() {
		return textBand() + (warning ? SIGN + bob / 2 : 0);
	}

	/** Over or under a horizontal strip, the band the text takes: one line, every slot's text on it; none with the bar. */
	public int textBand() {
		return text ? lineHeight : 0;
	}

	/** Whether the texts are drawn smaller than asked, so a horizontal strip's texts fit their slots. */
	public boolean fitted() {
		return textScale < chosenScale;
	}

	/** Beside a vertical strip: the widest text with a unit either side, and a warning sign. */
	private int besideWidth() {
		return (textWidth > 0 ? textWidth + 2 : 0) + (warning ? SIGN : 0);
	}

	/** How far text wider than a slot reaches past the ends of a horizontal strip. */
	private int overhang() {
		return Math.max(0, (textWidth + 1) / 2 - SIZE / 2);
	}

	/** Where a slot's text starts across a horizontal strip, centred on the slot at {@code slotX} ({@code width} at the text's size). */
	public static int centred(int slotX, int width) {
		return slotX + SIZE / 2 - width / 2;
	}

	/** Where a slot's text starts down a vertical strip, centred beside the slot at {@code slotY}. */
	public int besideY(int slotY) {
		return slotY + (SIZE - lineHeight) / 2;
	}

	// ---------- Free placement ----------

	/**
	 * Places the box by {@code placement} on a {@code screenW × screenH} GUI-unit screen at {@code guiScale} pixels a unit, at its
	 * size step, exactly as the HUD editor places a module (whole pixels, kept on screen): {@link #left}, {@link #top},
	 * {@link #drawnWidth}, {@link #drawnHeight} and {@link #scale}. Call after {@link #measure}.
	 */
	public void place(HudPlacement placement, float screenW, float screenH, int guiScale) {
		int g = Math.max(1, guiScale);
		scale = placement.scale();
		float u = scale * g;
		drawnWidth = (float) Math.round(boxWidth * u) / g;
		drawnHeight = (float) Math.round(boxHeight * u) / g;
		left = (float) Math.round(placement.clampedLeft(screenW, drawnWidth) * g) / g;
		top = (float) Math.round(placement.clampedTop(screenH, drawnHeight) * g) / g;
	}

	/**
	 * Free placement's Auto side for the text and signs: under a horizontal strip in the top half of the screen (else over it),
	 * right of a vertical strip in the left half (else left of it), so they face the middle of the screen. After {@link #place}.
	 */
	public boolean autoAfter(float screenW, float screenH) {
		return vertical ? left + drawnWidth / 2 < screenW / 2 : top + drawnHeight / 2 < screenH / 2;
	}
}
