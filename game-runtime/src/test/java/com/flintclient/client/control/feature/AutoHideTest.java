package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Autohide HUD's timing: hidden after the delay, sliding over the animation, back at once on a change. */
class AutoHideTest {
	private static void ticks(AutoHide hide, int n, long health, long food, long hotbar) {
		for (int i = 0; i < n; i++) {
			hide.tick(health, food, hotbar);
		}
	}

	@Test
	void hidesAfterTheDelayAndComesBackOnAChange() {
		AutoHide hide = new AutoHide();
		assertEquals(0, hide.hidden(AutoHide.HEALTH, false, 40, 10, 0), "nothing seen yet: shown");
		ticks(hide, 41, 1, 1, 1);
		assertEquals(0, hide.hidden(AutoHide.HEALTH, false, 40, 10, 0), "40 ticks still: about to go");
		ticks(hide, 5, 1, 1, 1);
		float sliding = hide.hidden(AutoHide.HEALTH, false, 40, 10, 0);
		assertTrue(sliding > 0 && sliding < 1, "sliding: " + sliding);
		ticks(hide, 10, 1, 1, 1);
		assertEquals(1, hide.hidden(AutoHide.HEALTH, false, 40, 10, 0), "gone");
		ticks(hide, 1, 2, 1, 1);
		assertEquals(0, hide.hidden(AutoHide.HEALTH, false, 40, 10, 0), "health changed: back at once");
		assertEquals(0, hide.hidden(AutoHide.FOOD, false, 40, 10, 0), "together: every part comes back");
	}

	@Test
	void separatelyEachPartKeepsItsOwnTimer() {
		AutoHide hide = new AutoHide();
		ticks(hide, 60, 1, 1, 1);
		ticks(hide, 1, 1, 1, 2);
		assertEquals(0, hide.hidden(AutoHide.HOTBAR, true, 40, 0, 0), "the hotbar changed");
		assertEquals(1, hide.hidden(AutoHide.HEALTH, true, 40, 0, 0), "health didn't");
	}

	@Test
	void noAnimationHidesAtOnceAndResetShowsAll() {
		AutoHide hide = new AutoHide();
		ticks(hide, 42, 1, 1, 1);
		assertEquals(1, hide.hidden(AutoHide.FOOD, false, 40, 0, 0));
		hide.reset();
		assertEquals(0, hide.hidden(AutoHide.FOOD, false, 40, 0, 0));
	}
}
