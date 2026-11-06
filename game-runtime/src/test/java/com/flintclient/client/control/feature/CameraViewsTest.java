package com.flintclient.client.control.feature;

import static com.flintclient.client.control.feature.CameraViews.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Snaplook's keys, and switching to a wished view and back without fighting the player's own F5. */
class CameraViewsTest {
	private static final int FIRST = 0;
	private static final int BACK = 1;
	private static final int FRONT = 2;

	@Test
	void aWishedViewIsShownThenTheOwnViewComesBack() {
		CameraViews views = new CameraViews();
		assertEquals(NONE, views.update(NONE, FIRST), "no wish: nothing changes");
		assertEquals(BACK, views.update(BACK, FIRST), "gliding starts: behind");
		assertTrue(views.holding());
		assertEquals(NONE, views.update(BACK, BACK), "already there");
		assertEquals(FRONT, views.update(FRONT, BACK), "another wish takes over");
		assertEquals(FIRST, views.update(NONE, FRONT), "the wish ends: first person again");
		assertFalse(views.holding());
	}

	@Test
	void aWishForTheViewYouHaveChangesNothingAfter() {
		CameraViews views = new CameraViews();
		assertEquals(NONE, views.update(BACK, BACK));
		assertEquals(NONE, views.update(NONE, BACK), "you were behind already: you stay");
	}

	@Test
	void pressingF5DuringAWishMakesTheViewYours() {
		CameraViews views = new CameraViews();
		views.update(BACK, FIRST);
		assertEquals(NONE, views.update(BACK, FRONT), "you pressed F5: left alone");
		assertEquals(NONE, views.update(BACK, FRONT), "and the same wish isn't applied again");
		assertEquals(NONE, views.update(NONE, FRONT), "nothing is switched back when it ends");
		assertEquals(BACK, views.update(BACK, FRONT), "a new wish applies again");
	}

	@Test
	void releaseGivesTheViewBack() {
		CameraViews views = new CameraViews();
		views.update(FRONT, FIRST);
		assertEquals(FIRST, views.release(FRONT));
		assertEquals(NONE, views.release(FIRST));
	}

	@Test
	void snaplookHoldUsesTheHeldKeyFirstKeyFirst() {
		CameraViews views = new CameraViews();
		assertEquals(NONE, views.snaplook(true, false, BACK, false, FRONT));
		assertEquals(BACK, views.snaplook(true, true, BACK, false, FRONT));
		assertEquals(BACK, views.snaplook(true, true, BACK, true, FRONT));
		assertEquals(FRONT, views.snaplook(true, false, BACK, true, FRONT));
		assertEquals(NONE, views.snaplook(true, false, BACK, false, FRONT));
	}

	@Test
	void snaplookTogglePressesSwitchAndSwitchBack() {
		CameraViews views = new CameraViews();
		assertEquals(BACK, views.snaplook(false, true, BACK, false, FRONT), "a press switches");
		assertEquals(BACK, views.snaplook(false, true, BACK, false, FRONT), "holding it doesn't press again");
		assertEquals(BACK, views.snaplook(false, false, BACK, false, FRONT), "it stays after letting go");
		assertEquals(FRONT, views.snaplook(false, false, BACK, true, FRONT), "the other key switches to its view");
		views.snaplook(false, false, BACK, false, FRONT);
		assertEquals(NONE, views.snaplook(false, false, BACK, true, FRONT), "its key again switches back");
	}
}
