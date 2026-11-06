package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import org.junit.jupiter.api.Test;

class ArmorLayoutTest {
	/** Minecraft's font: a digit is 6 units, "%" is 6. */
	private static final int DIGITS_3 = 18;

	@Test
	void atFullSizeItIsUkusArmorHud() {
		ArmorLayout l = new ArmorLayout();
		// Four pieces in a row with the bar only: the strip and nothing else.
		l.measure(4, false, false, 0, 100, false, 3);
		assertEquals(82, l.stripWidth, "22 + 3 × 20");
		assertEquals(22, l.stripHeight);
		assertEquals(82, l.boxWidth);
		assertEquals(22, l.boxHeight);
		// Text and the warning sign over the strip: a line of 9, a sign of 10 and half the bob.
		l.measure(4, false, true, DIGITS_3, 100, true, 3);
		l.side(false);
		assertEquals(9, l.lineHeight);
		assertEquals(22 + 9 + 10 + 1, l.boxHeight);
		assertEquals(20, l.stripY, "the strip sits under its text and signs");
		assertEquals(0, l.stripX, "three digits fit over a slot");
		l.side(true);
		assertEquals(0, l.stripY, "under the strip: the strip is on top");
		// uku's text placement: centred on the slot, x + 11 - width / 2.
		assertEquals(11 - 17 / 2, ArmorLayout.centred(0, 17));
		// A column: the text beside each slot, centred down it ((22 - 9) / 2 = 6 units down), then a warning sign.
		l.measure(5, true, true, DIGITS_3, 100, true, 3);
		l.side(false);
		assertEquals(22, l.stripWidth);
		assertEquals(102, l.stripHeight, "22 + 4 × 20");
		assertEquals(22 + DIGITS_3 + 2 + 10, l.boxWidth);
		assertEquals(102, l.boxHeight);
		assertEquals(DIGITS_3 + 2 + 10, l.stripX, "left of the strip: the strip is on the right");
		assertEquals(6, l.besideY(0));
		l.side(true);
		assertEquals(0, l.stripX);
	}

	@Test
	void theTextHasItsOwnSizeFromHalfToDouble() {
		ArmorLayout l = new ArmorLayout();
		l.measure(1, false, true, DIGITS_3, 200, false, 0);
		assertEquals(2.0F, l.textScale);
		assertEquals(18, l.lineHeight, "a line of 9 at twice the size");
		assertEquals(36, l.textWidth);
		assertEquals(36 / 2 - 11, (l.boxWidth - l.stripWidth) / 2, "wider than its slot, it reaches past the strip's ends, still centred");
		assertEquals(22 + 18, l.boxHeight);
		l.measure(1, false, true, DIGITS_3, 50, false, 0);
		assertEquals(0.5F, l.textScale);
		assertEquals(5, l.lineHeight, "4.5 rounds to 5");
		assertEquals(9, l.textWidth);
		assertEquals(22, l.boxWidth, "within the slot");
		// Out of range sizes are held to 50 and 200 %.
		l.measure(1, false, true, DIGITS_3, 10, false, 0);
		assertEquals(0.5F, l.textScale);
		l.measure(1, false, true, DIGITS_3, 400, false, 0);
		assertEquals(2.0F, l.textScale);
		// Centred at any size: the text's middle is the slot's middle (to the unit).
		for (int width = 1; width < 80; width++) {
			int start = ArmorLayout.centred(100, width);
			assertTrue(Math.abs(start + width / 2.0F - 111) <= 0.5F, "width " + width);
		}
		// Beside a column, centred down the slot at every size.
		for (int percent = 50; percent <= 200; percent += 5) {
			l.measure(2, true, true, DIGITS_3, percent, false, 0);
			float middle = l.besideY(40) + l.lineHeight / 2.0F;
			assertTrue(Math.abs(middle - 51) <= 0.5F, percent + " %");
		}
	}

	@Test
	void theItemSlotsMakeTheStripLonger() {
		ArmorLayout l = new ArmorLayout();
		l.measure(4 + ArmorPieces.KINDS, true, false, 0, 100, false, 0);
		assertEquals(22 + 10 * 20, l.boxHeight, "four pieces and every item slot, one under another");
		l.measure(0, false, true, DIGITS_3, 100, true, 3);
		assertEquals(0, l.stripWidth, "nothing to show");
	}

	@Test
	void everySlotsTextSharesOneLineOrOneColumn() {
		ArmorLayout l = new ArmorLayout();
		// A row: one line over (or under) the strip for every slot, so Text position moves them all together.
		l.measure(6, false, true, 24, 150, true, 3);
		assertEquals(l.lineHeight, l.textBand(), "one line, never two");
		assertEquals(22 + l.lineHeight + 10 + 1, l.boxHeight);
		l.side(false);
		int over = l.stripY;
		l.side(true);
		assertEquals(0, l.stripY, "under: the same line for every slot, past the strip");
		assertEquals(l.lineHeight + 10 + 1, over, "over: the same line for every slot, before the strip");
		// A column: one column beside the strip, each text centred down its own slot at the same offset.
		l.measure(6, true, true, 24, 150, true, 3);
		for (int slot = 0; slot < 6; slot++) {
			int slotY = slot * ArmorLayout.STEP;
			assertEquals(l.besideY(0), l.besideY(slotY) - slotY, "slot " + slot);
		}
	}

	@Test
	void textTooWideForARowShrinksAllAlikeUntilTheWidestFits() {
		ArmorLayout l = new ArmorLayout();
		// Three digits fit over a slot at full size: the size asked for, exactly (uku's look).
		l.measure(4, false, true, DIGITS_3, 100, true, 3);
		assertFalse(l.fitted());
		assertEquals(1.0F, l.textScale);
		assertEquals(9, l.lineHeight);
		// Four digits at 150 % bold (a netherite sword: "2031", 28 units bold at full size), bold three digits, bigger text: every
		// text shrinks by one factor so the widest is the slot pitch less a 2-unit gap, one size and one line.
		for (int[] wide : new int[][] {{28, 150}, {24, 100}, {21, 100}, {DIGITS_3, 125}, {28, 200}}) {
			l.measure(4, false, true, wide[0], wide[1], true, 3);
			String what = wide[0] + " at " + wide[1] + " %";
			assertTrue(l.fitted(), what);
			assertEquals(wide[1] / 100.0F, l.chosenScale, what);
			assertEquals(ArmorLayout.FIT / (float) wide[0], l.textScale, 1e-6, what);
			assertEquals(ArmorLayout.FIT, l.textWidth, what);
			assertTrue(ArmorLayout.centred(0, l.textWidth) + l.textWidth + 2 <= ArmorLayout.centred(ArmorLayout.STEP, l.textWidth),
				what + ": a gap between neighbours");
			assertEquals(Math.round(ArmorLayout.LINE * l.textScale), l.lineHeight, what + ": the line shrinks with it");
			assertEquals(l.stripWidth, l.boxWidth, what + ": nothing reaches past the strip");
		}
		// Only a row of slots needs it: a column, a single slot and the bar keep the size asked for.
		l.measure(4, true, true, 28, 200, false, 0);
		assertFalse(l.fitted());
		assertEquals(2.0F, l.textScale);
		l.measure(1, false, true, 28, 200, false, 0);
		assertFalse(l.fitted());
		l.measure(4, false, false, 28, 200, false, 0);
		assertFalse(l.fitted());
		assertEquals(0, l.textBand());
	}

	@Test
	void freePlacementKeepsItWholeOnScreenAtAnyGuiScale() {
		ArmorLayout l = new ArmorLayout();
		l.measure(8, true, true, 24, 150, true, 3);
		l.side(true);
		float[][] screens = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {960, 540}, {1920, 1080}, {200, 120}};
		HudPlacement[] placements = {HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), new HudPlacement(Anchor.TOP_LEFT, -500, -500, 1),
			new HudPlacement(Anchor.BOTTOM_RIGHT, -900, -900, 2), new HudPlacement(Anchor.CENTER, 0, 0, 1.5F),
			new HudPlacement(Anchor.RIGHT, 30, 400, 0.5F)};
		for (float[] screen : screens) {
			for (int guiScale = 1; guiScale <= 6; guiScale++) {
				for (HudPlacement p : placements) {
					l.place(p, screen[0], screen[1], guiScale);
					String where = p + " on " + screen[0] + "x" + screen[1] + " at " + guiScale;
					assertEquals(p.scale(), l.scale, where);
					assertEquals(Math.round(l.boxWidth * p.scale() * guiScale), Math.round(l.drawnWidth * guiScale), where + ": whole pixels");
					assertEquals(l.left * guiScale, Math.round(l.left * guiScale), 1e-3, where + ": on a pixel");
					assertEquals(l.top * guiScale, Math.round(l.top * guiScale), 1e-3, where + ": on a pixel");
					if (l.drawnWidth <= screen[0]) {
						assertTrue(l.left >= 0 && l.left + l.drawnWidth <= screen[0] + 0.5F / guiScale, where);
					} else {
						assertEquals(0, l.left, where + ": wider than the screen, from its left edge");
					}
					if (l.drawnHeight <= screen[1]) {
						assertTrue(l.top >= 0 && l.top + l.drawnHeight <= screen[1] + 0.5F / guiScale, where);
					} else {
						assertEquals(0, l.top, where);
					}
				}
			}
		}
	}

	@Test
	void freePlacementIsWhereTheHudEditorPutIt() {
		ArmorLayout l = new ArmorLayout();
		l.measure(4, false, false, 0, 100, false, 0);
		// The editor stores a corner and an offset; the box is drawn exactly there, at its size step.
		l.place(new HudPlacement(Anchor.TOP_LEFT, 10, 20, 1), 640, 360, 2);
		assertEquals(10, l.left);
		assertEquals(20, l.top);
		assertEquals(82, l.drawnWidth);
		l.place(new HudPlacement(Anchor.BOTTOM_RIGHT, 4, 4, 2), 640, 360, 2);
		assertEquals(164, l.drawnWidth);
		assertEquals(44, l.drawnHeight);
		assertEquals(640 - 4 - 164, l.left);
		assertEquals(360 - 4 - 44, l.top);
		// The same as HudPlacement places any module (what the HUD editor outlines).
		HudPlacement p = new HudPlacement(Anchor.CENTER, 13.3F, -7.7F, 1.25F);
		l.place(p, 683, 384, 3);
		assertEquals(Math.round(p.clampedLeft(683, l.drawnWidth) * 3) / 3.0F, l.left, 1e-4);
		assertEquals(Math.round(p.clampedTop(384, l.drawnHeight) * 3) / 3.0F, l.top, 1e-4);
	}

	@Test
	void autoPutsTheTextTowardsTheMiddleOfTheScreen() {
		ArmorLayout l = new ArmorLayout();
		l.measure(4, false, true, DIGITS_3, 100, false, 0);
		l.place(new HudPlacement(Anchor.TOP_LEFT, 4, 4, 1), 640, 360, 2);
		assertTrue(l.autoAfter(640, 360), "at the top: under the strip");
		l.place(new HudPlacement(Anchor.BOTTOM_LEFT, 4, 4, 1), 640, 360, 2);
		assertFalse(l.autoAfter(640, 360), "at the bottom: over it");
		l.measure(4, true, true, DIGITS_3, 100, false, 0);
		l.place(new HudPlacement(Anchor.LEFT, 4, 0, 1), 640, 360, 2);
		assertTrue(l.autoAfter(640, 360), "on the left: to its right");
		l.place(new HudPlacement(Anchor.RIGHT, 4, 0, 1), 640, 360, 2);
		assertFalse(l.autoAfter(640, 360), "on the right: to its left");
	}
}
