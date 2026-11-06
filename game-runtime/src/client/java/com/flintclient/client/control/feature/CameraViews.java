package com.flintclient.client.control.feature;

/**
 * Snaplook and Auto Perspective's camera views: which view they want, and when to switch to it and back. Views are
 * Minecraft's perspectives by number (0 first person, 1 behind, 2 in front); {@link #NONE} is no wish. While a wish holds,
 * the camera shows it; when the wish ends, the view you had before comes back. Pressing F5 yourself while a wish holds
 * makes the view yours: that wish isn't applied again until it changes, and nothing is switched back afterwards. Freelook
 * is never interrupted: the caller doesn't update this while it is on. No Minecraft classes.
 */
public final class CameraViews {
	public static final int NONE = -1;
	/** The view this switched to and still holds, or NONE. */
	private int applied = NONE;
	/** The view to give back when the wish ends. */
	private int restore = NONE;
	/** A wish the player overrode with F5: left alone until the wish changes. */
	private int ignored = NONE;
	private final boolean[] was = new boolean[2];
	/** Snaplook's toggled view (toggle mode) and the key (0 or 1) that set it. */
	private int toggled = NONE;
	private int toggledBy = -1;

	/**
	 * Snaplook's wish from its two keys: held (hold mode, the first key wins) or toggled by a fresh press (another press of
	 * the same key switches back; the other key switches to its own view).
	 */
	public int snaplook(boolean hold, boolean firstDown, int firstView, boolean secondDown, int secondView) {
		boolean firstPressed = firstDown && !was[0];
		boolean secondPressed = secondDown && !was[1];
		was[0] = firstDown;
		was[1] = secondDown;
		if (hold) {
			toggled = NONE;
			toggledBy = -1;
			return firstDown ? firstView : secondDown ? secondView : NONE;
		}
		if (firstPressed) {
			press(0, firstView);
		} else if (secondPressed) {
			press(1, secondView);
		}
		return toggled;
	}

	private void press(int key, int view) {
		if (toggledBy == key) {
			toggled = NONE;
			toggledBy = -1;
		} else {
			toggled = view;
			toggledBy = key;
		}
	}

	/**
	 * One step: {@code wanted} is the view the mods want now (or NONE), {@code current} the camera's view. Returns the view
	 * to switch the camera to, or NONE to leave it as it is.
	 */
	public int update(int wanted, int current) {
		if (applied != NONE && current != applied) {
			// You pressed F5 while a view was held: yours now.
			ignored = wanted;
			applied = NONE;
			restore = NONE;
			return NONE;
		}
		if (wanted != ignored) {
			ignored = NONE;
		}
		if (wanted == NONE || wanted == ignored) {
			if (applied == NONE) {
				return NONE;
			}
			int back = restore;
			applied = NONE;
			restore = NONE;
			return back == current ? NONE : back;
		}
		if (applied == NONE) {
			restore = current;
		}
		applied = wanted;
		return wanted == current ? NONE : wanted;
	}

	/** Leaving a world or turning the mods off: the view to give back now (or NONE), forgetting the rest. */
	public int release(int current) {
		toggled = NONE;
		toggledBy = -1;
		was[0] = false;
		was[1] = false;
		int back = update(NONE, current);
		ignored = NONE;
		return back;
	}

	/** Whether a wish holds the camera now. */
	public boolean holding() {
		return applied != NONE;
	}
}
