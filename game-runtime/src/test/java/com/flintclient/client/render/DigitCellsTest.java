package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DigitCellsTest {
	/** Inter Medium's advances at 4.6 units and scale 3, roughly: digits differ, as Inter's do. */
	private static int[] advances() {
		int[] a = new int[DigitCells.SLOTS];
		int[] digits = {9, 6, 9, 9, 9, 8, 9, 8, 9, 9};
		System.arraycopy(digits, 0, a, 0, 10);
		a[DigitCells.slot('-')] = 6;
		a[DigitCells.slot('−')] = 9;
		a[DigitCells.slot(' ')] = 4;
		a[DigitCells.slot('.')] = 4;
		a[DigitCells.slot(':')] = 4;
		a[DigitCells.slot('/')] = 5;
		a[DigitCells.slot('%')] = 14;
		return a;
	}

	@Test
	void everySupportedCharacterHasASlotAndOthersDoNot() {
		for (char c : "0123456789-− .:/%".toCharArray()) {
			assertTrue(DigitCells.slot(c) >= 0, "slot for " + c);
			assertEquals(c, DigitCells.CHARS.charAt(DigitCells.slot(c)));
		}
		assertEquals(-1, DigitCells.slot('a'));
		assertEquals(-1, DigitCells.slot(','));
	}

	@Test
	void digitsSignsAndSpacesShareTheWidestDigitsCell() {
		int[] widths = new int[DigitCells.SLOTS];
		int[] offsets = new int[DigitCells.SLOTS];
		DigitCells.layout(advances(), widths, offsets);
		for (char c : "0123456789-− ".toCharArray()) {
			assertEquals(9, widths[DigitCells.slot(c)], "cell of " + c);
		}
		// The narrow "1" is centred in its cell.
		assertEquals(1, offsets[DigitCells.slot('1')]);
		assertEquals(0, offsets[DigitCells.slot('0')]);
		// Separators keep their own advance.
		assertEquals(4, widths[DigitCells.slot('.')]);
		assertEquals(14, widths[DigitCells.slot('%')]);
	}

	@Test
	void aRunsWidthDependsOnItsPatternNotItsDigits() {
		int[] widths = new int[DigitCells.SLOTS];
		DigitCells.layout(advances(), widths, new int[DigitCells.SLOTS]);
		int ones = DigitCells.width(widths, "11.1");
		assertEquals(ones, DigitCells.width(widths, "88.8"));
		assertEquals(ones, DigitCells.width(widths, "−4.7"), "a sign takes a digit's cell");
		assertEquals(9 * 3 + 4, ones);
		char[] buffer = "144 fps".toCharArray();
		assertEquals(DigitCells.width(widths, "144 "), DigitCells.width(widths, buffer, buffer.length), "letters take no space");
		assertEquals(DigitCells.width(widths, "14"), DigitCells.width(widths, buffer, 2));
	}
}
