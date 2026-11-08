package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MenuCloseTest {
	@Test
	void anyOtherKeyDuringTheFadeLeavesAtOnceAndGoesToTheGame() {
		// Esc, E, T, W...: the menu doesn't swallow the player's next key while it fades out.
		assertEquals(MenuClose.Press.LEAVE_NOW, MenuClose.press(false, false));
		assertEquals(MenuClose.Press.LEAVE_NOW, MenuClose.press(false, true), "only the menu's own key can turn the close round");
	}

	@Test
	void theMenuKeyTurnsTheCloseRoundOnlyWhereTheScreenReopens() {
		assertEquals(MenuClose.Press.REOPEN, MenuClose.press(true, true));
		assertEquals(MenuClose.Press.IGNORE, MenuClose.press(true, false), "held, or a screen that can't reopen: the fade goes on");
	}

	@Test
	void aFadeEndsByItselfOnceOverdue() {
		MenuClose fade = new MenuClose();
		assertFalse(fade.closing());
		assertFalse(fade.overdue(10_000), "not closing: never overdue");
		fade.begin(1_000);
		assertTrue(fade.closing());
		assertFalse(fade.overdue(1_000 + 120), "the usual 120 ms fade");
		assertFalse(fade.overdue(1_000 + MenuClose.TIMEOUT_MILLIS));
		assertTrue(fade.overdue(1_000 + MenuClose.TIMEOUT_MILLIS + 1), "no frames came: it leaves anyway");
	}

	@Test
	void askingToCloseAgainKeepsTheFirstStart() {
		MenuClose fade = new MenuClose();
		fade.begin(1_000);
		fade.begin(1_500);
		assertTrue(fade.overdue(1_000 + MenuClose.TIMEOUT_MILLIS + 1), "measured from the first request, so it can't be put off forever");
	}

	@Test
	void endingOrTurningRoundClearsIt() {
		MenuClose fade = new MenuClose();
		fade.begin(1_000);
		fade.end();
		assertFalse(fade.closing());
		assertFalse(fade.overdue(1_000_000));
		fade.begin(5_000);
		assertFalse(fade.overdue(5_000 + MenuClose.TIMEOUT_MILLIS), "a new fade starts its own clock");
		assertTrue(fade.overdue(5_000 + MenuClose.TIMEOUT_MILLIS + 1));
	}
}
