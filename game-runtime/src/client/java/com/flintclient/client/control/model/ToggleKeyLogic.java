package com.flintclient.client.control.model;

/**
 * Whether the key that opens the Control Center should close it again. While a screen is open Minecraft sends
 * both presses and key repeats to its {@code keyPressed}, with nothing to tell them apart, and the press that
 * opened the screen may still be held. So the key closes the screen only on a fresh press: one that follows
 * a release seen since opening. It never closes while the player is typing in a text field or choosing a new
 * key binding (the key is input there, not a command), and the repeats of such a press are ignored too.
 */
public final class ToggleKeyLogic {
	private boolean releaseSeen;
	private boolean down;

	/**
	 * Call when the screen opens. Pass whether the toggle key is physically down right now: a quick tap is often
	 * released before the screen exists, and then no release would ever arrive.
	 */
	public void opened(boolean toggleKeyDown) {
		down = toggleKeyDown;
		releaseSeen = !toggleKeyDown;
	}

	/** A key release reached the screen. */
	public void keyReleased(boolean isToggleKey) {
		if (isToggleKey) {
			down = false;
			releaseSeen = true;
		}
	}

	/**
	 * A key press (or repeat) reached the screen; returns true when it should close the screen.
	 *
	 * @param isToggleKey the key is bound to the Control Center mapping
	 * @param textFieldFocused a text field has focus
	 * @param capturingKey a key binding is being chosen
	 */
	public boolean keyPressed(boolean isToggleKey, boolean textFieldFocused, boolean capturingKey) {
		if (!isToggleKey) {
			return false;
		}
		boolean repeat = down;
		down = true;
		return !repeat && releaseSeen && !textFieldFocused && !capturingKey;
	}

	/** Whether the toggle key has been released since the screen opened. */
	public boolean releaseSeen() {
		return releaseSeen;
	}
}
