package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.TextAlign;
import org.junit.jupiter.api.Test;

/** The FPS counter's box: the one layout the HUD, the editor and the preview share. */
class FpsBoxTest {
	private static final float U = 2;
	private final FpsBox box = new FpsBox();

	private void background(int textW, FpsSizing sizing, int width, int height, TextAlign align) {
		box.layout(textW, 14, U, FpsDisplayMode.BACKGROUND, true, sizing, width, height, false, 1, 3, align);
	}

	@Test
	void justTextAndBracketsAreExactlyTheirText() {
		for (FpsDisplayMode mode : new FpsDisplayMode[] {FpsDisplayMode.TEXT, FpsDisplayMode.BRACKETS}) {
			box.layout(60, 14, U, mode, true, FpsSizing.STATIC, 120, 40, true, 2, 4, TextAlign.RIGHT);
			assertEquals(60, box.width, mode.name());
			assertEquals(14, box.height, mode.name());
			assertEquals(0, box.textX);
			assertEquals(0, box.textY);
			assertFalse(box.box, "no box, so no fill or border either: " + mode);
			assertFalse(box.fill);
			assertEquals(0, box.borderPx);
		}
	}

	@Test
	void dynamicFitsTheTextWithPadding() {
		background(40, FpsSizing.DYNAMIC, 120, 13, TextAlign.LEFT);
		int pad = Math.round(FpsBox.PAD_X * U);
		assertEquals(40 + 2 * pad, box.width, "the set width is ignored");
		assertEquals(pad, box.textX);
		background(54, FpsSizing.DYNAMIC, 120, 13, TextAlign.LEFT);
		assertEquals(54 + 2 * pad, box.width, "a four-digit number widens it");
	}

	@Test
	void staticKeepsItsWidthButNeverClipsTheText() {
		background(40, FpsSizing.STATIC, 56, 13, TextAlign.CENTER);
		assertEquals(112, box.width, "56 units at 2 px");
		assertEquals((112 - 40) / 2, box.textX, "centred");
		background(200, FpsSizing.STATIC, 24, 13, TextAlign.CENTER);
		int minPad = Math.round(FpsBox.MIN_PAD_X * U);
		assertEquals(200 + 2 * minPad, box.width, "too narrow: it grows to the text plus a little room");
		assertTrue(box.textX >= minPad && box.textX + 200 <= box.width, "the text stays inside");
	}

	@Test
	void alignmentPlacesTheTextInASpareWidth() {
		background(40, FpsSizing.STATIC, 80, 13, TextAlign.LEFT);
		assertEquals(Math.round(FpsBox.PAD_X * U), box.textX);
		background(40, FpsSizing.STATIC, 80, 13, TextAlign.RIGHT);
		assertEquals(160 - 40 - Math.round(FpsBox.PAD_X * U), box.textX);
		background(40, FpsSizing.STATIC, 80, 13, TextAlign.CENTER);
		assertEquals(60, box.textX);
	}

	@Test
	void heightCentresTheTextAndNeverGoesBelowIt() {
		background(40, FpsSizing.DYNAMIC, 0, 20, TextAlign.CENTER);
		assertEquals(40, box.height);
		assertEquals(13, box.textY, "(40 - 14) / 2");
		background(40, FpsSizing.DYNAMIC, 0, 3, TextAlign.CENTER);
		assertEquals(14 + 2 * Math.round(FpsBox.MIN_PAD_Y * U), box.height, "too short: it grows to the text");
	}

	@Test
	void backgroundOffKeepsTheBoxForTheBorder() {
		box.layout(40, 14, U, FpsDisplayMode.BACKGROUND, false, FpsSizing.DYNAMIC, 0, 13, true, 2, 3, TextAlign.CENTER);
		assertTrue(box.box);
		assertFalse(box.fill, "no fill");
		assertEquals(2, box.borderPx, "two half units at 2 px per unit");
		box.layout(40, 14, U, FpsDisplayMode.BACKGROUND, true, FpsSizing.DYNAMIC, 0, 13, false, 4, 3, TextAlign.CENTER);
		assertEquals(0, box.borderPx, "border off");
		box.layout(40, 14, 0.5F, FpsDisplayMode.BACKGROUND, true, FpsSizing.DYNAMIC, 0, 13, true, 1, 3, TextAlign.CENTER);
		assertEquals(1, box.borderPx, "a hairline is never under a pixel");
	}

	@Test
	void cornersNeverExceedHalfTheBox() {
		box.layout(10, 14, 4, FpsDisplayMode.BACKGROUND, true, FpsSizing.DYNAMIC, 0, 9, false, 1, 8, TextAlign.CENTER);
		assertEquals(Math.min(box.width, box.height) / 2, box.radiusPx);
	}

	@Test
	void aDynamicWidthGrowsAtOnceButShrinksOnlyOnceTheNarrowerOneHolds() {
		FpsBox.Hold hold = new FpsBox.Hold();
		long second = FpsBox.Hold.SHRINK_AFTER_NANOS;
		assertEquals(80, hold.width(80, 0));
		assertEquals(96, hold.width(96, 10), "wider: at once (the text is never clipped)");
		assertEquals(96, hold.width(80, 20), "narrower: held");
		assertEquals(96, hold.width(80, 20 + second / 2));
		assertEquals(96, hold.width(96, 20 + second - 1), "back up: still held");
		assertEquals(96, hold.width(80, 30 + second), "a new narrower spell starts its own wait");
		assertEquals(80, hold.width(80, 30 + 2 * second), "held a whole second: it shrinks");
		hold.reset();
		assertEquals(60, hold.width(60, 0), "after a settings change the width is taken as it is");
	}
}
