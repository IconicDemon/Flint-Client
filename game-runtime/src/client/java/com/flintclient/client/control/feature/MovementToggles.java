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

	/**
	 * One tick: the physical keys and whether each toggle is on. A press edge flips the latch; when a toggle is off its
	 * latch clears.
	 */
	public void tick(boolean sprintKey, boolean sneakKey, boolean toggleSprint, boolean toggleSneak) {
		sprintDown = sprintKey;
		sneakDown = sneakKey;
		if (!toggleSprint) {
			sprintLatched = false;
		} else if (sprintKey && !sprintWasDown) {
			sprintLatched = !sprintLatched;
		}
		if (!toggleSneak) {
			sneakLatched = false;
		} else if (sneakKey && !sneakWasDown) {
			sneakLatched = !sneakLatched;
		}
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
