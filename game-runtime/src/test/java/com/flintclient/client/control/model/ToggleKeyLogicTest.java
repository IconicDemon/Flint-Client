package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToggleKeyLogicTest {
	@Test
	void theOpeningPressAndItsRepeatsNeverClose() {
		ToggleKeyLogic logic = new ToggleKeyLogic();
		logic.opened(true);
		assertFalse(logic.releaseSeen());
		for (int i = 0; i < 5; i++) {
			assertFalse(logic.keyPressed(true, false, false), "key repeat " + i);
		}
		logic.keyReleased(true);
		assertTrue(logic.releaseSeen());
		assertTrue(logic.keyPressed(true, false, false), "a fresh press after the release closes");
	}

	@Test
	void aQuickTapReleasedBeforeTheScreenOpenedStillLetsTheNextPressClose() {
		ToggleKeyLogic logic = new ToggleKeyLogic();
		logic.opened(false);
		assertTrue(logic.keyPressed(true, false, false));
	}

	@Test
	void otherKeysNeitherCloseNorCountAsTheRelease() {
		ToggleKeyLogic logic = new ToggleKeyLogic();
		logic.opened(true);
		logic.keyReleased(false);
		assertFalse(logic.releaseSeen());
		assertFalse(logic.keyPressed(false, false, false));
		logic.keyReleased(true);
		assertFalse(logic.keyPressed(false, false, false), "another key never closes");
		assertTrue(logic.keyPressed(true, false, false));
	}

	@Test
	void notWhileTyping() {
		ToggleKeyLogic logic = new ToggleKeyLogic();
		logic.opened(false);
		assertFalse(logic.keyPressed(true, true, false), "a focused text field takes the key");
		assertFalse(logic.keyPressed(true, false, false), "its repeat doesn't close once the field loses focus");
		logic.keyReleased(true);
		assertTrue(logic.keyPressed(true, false, false));
	}

	@Test
	void notWhileChoosingAKeyBinding() {
		ToggleKeyLogic logic = new ToggleKeyLogic();
		logic.opened(false);
		assertFalse(logic.keyPressed(true, false, true), "the press becomes the new binding");
		assertFalse(logic.keyPressed(true, false, false), "holding it after capture ends doesn't close");
		logic.keyReleased(true);
		assertTrue(logic.keyPressed(true, false, false));
	}

	@Test
	void reopeningStartsOver() {
		ToggleKeyLogic logic = new ToggleKeyLogic();
		logic.opened(false);
		logic.keyReleased(true);
		logic.opened(true);
		assertFalse(logic.keyPressed(true, false, false));
	}
}
