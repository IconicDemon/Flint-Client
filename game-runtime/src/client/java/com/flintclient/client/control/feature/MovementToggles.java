package com.flintclient.client.control.feature;

/**
 * Toggle Sprint and Toggle Sneak: a tap of the key latches it on, the next tap off. The version layer reports each
 * tick whether the physical key is down and asks whether to hold sprint or sneak; holding is exactly what the key would
 * do if you held it, nothing more. Pure Java.
 */
public final class MovementToggles {
	private boolean sprintLatched;
	private boolean sneakLatched;
	private boolean sprintWasDown;
	private boolean sneakWasDown;
	private boolean sprintDown;
	private boolean sneakDown;

	/** {@link #tick(boolean, boolean, boolean, boolean, boolean)} while playing. */
	public void tick(boolean sprintKey, boolean sneakKey, boolean toggleSprint, boolean toggleSneak) {
		tick(sprintKey, sneakKey, toggleSprint, toggleSneak, true);
	}

	/**
	 * One tick: the physical keys, whether each toggle is on, and whether the player is playing (in a world, no screen
	 * open). A press edge flips the latch, but only a press that starts while playing: a key held while a screen was open
	 * (Shift for shift-clicks in the inventory, Ctrl for Ctrl+Q) and still held as it closes is not a tap, so it never
	 * latches sprint or sneak on (or off) by itself. When a toggle is off its latch clears.
	 */
	public void tick(boolean sprintKey, boolean sneakKey, boolean toggleSprint, boolean toggleSneak, boolean playing) {
		sprintDown = playing && sprintKey;
		sneakDown = playing && sneakKey;
		if (!toggleSprint) {
			sprintLatched = false;
		} else if (playing && sprintKey && !sprintWasDown) {
			sprintLatched = !sprintLatched;
		}
		if (!toggleSneak) {
			sneakLatched = false;
		} else if (playing && sneakKey && !sneakWasDown) {
			sneakLatched = !sneakLatched;
		}
		// The key's own state, screen or not: an edge needs it let go first.
		sprintWasDown = sprintKey;
		sneakWasDown = sneakKey;
	}

	/** Whether sprint should be held this tick by the toggle. */
	public boolean holdSprint() {
		return sprintLatched;
	}

	public boolean holdSneak() {
		return sneakLatched;
	}

	public boolean sprintKeyDown() {
		return sprintDown;
	}

	public boolean sneakKeyDown() {
		return sneakDown;
	}

	/** Lets go of both (a world change, the module turned off). */
	public void reset() {
		sprintLatched = false;
		sneakLatched = false;
		sprintWasDown = false;
		sneakWasDown = false;
	}
}
