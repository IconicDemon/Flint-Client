package com.flintclient.client.control.cc;

/**
 * When the first-world hint ("Press Right Shift to customise your HUD") is shown: once, on the first client
 * tick that has the player in a world with nothing else on screen (so never over a loading screen), and only
 * while the hint is still due and the Control Center key is bound to something it could name. Once shown, or
 * once found already shown, it is settled for the session and never asks again. Pure for tests.
 */
public final class HintGate {
	private boolean settled;

	/**
	 * Called every client tick; true exactly once, when the hint should be shown now.
	 *
	 * @param alreadyShown the saved "hint shown" flag
	 * @param playing a world is loaded and neither a screen nor an overlay is open
	 * @param keyBound the Control Center key is bound
	 */
	public boolean poll(boolean alreadyShown, boolean playing, boolean keyBound) {
		if (settled) {
			return false;
		}
		if (alreadyShown) {
			settled = true;
			return false;
		}
		if (!playing || !keyBound) {
			return false;
		}
		settled = true;
		return true;
	}

	/** Nothing more to do this session (shown, or found already shown). */
	public boolean settled() {
		return settled;
	}
}
