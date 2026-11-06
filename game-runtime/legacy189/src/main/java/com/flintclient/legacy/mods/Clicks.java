package com.flintclient.legacy.mods;

/**
 * Clicks per second for each mouse button, from the presses Minecraft hands to gameplay (not the ones a screen takes). The
 * last 64 presses of each button are kept in a ring of timestamps; counting the ones in the last second allocates nothing.
 */
public final class Clicks {
	private static final int SIZE = 64;
	private static final long[] LEFT = new long[SIZE];
	private static final long[] RIGHT = new long[SIZE];
	private static int left;
	private static int right;

	private Clicks() {
	}

	/** A press of {@code button} (0 left, 1 right). */
	public static void press(int button) {
		// The shared counter CPS and Keystrokes read (platform.HudData), as on every line.
		com.flintclient.client.platform.HudData.cps().press(button == 0 ? com.flintclient.client.control.hud.CpsCounter.LEFT
			: com.flintclient.client.control.hud.CpsCounter.RIGHT, com.flintclient.client.platform.HudData.nowMillis());
		long now = System.currentTimeMillis();
		if (button == 0) {
			LEFT[left++ & (SIZE - 1)] = now;
		} else if (button == 1) {
			RIGHT[right++ & (SIZE - 1)] = now;
		}
	}

	public static int left() {
		return count(LEFT);
	}

	public static int right() {
		return count(RIGHT);
	}

	private static int count(long[] ring) {
		long since = System.currentTimeMillis() - 1000;
		int n = 0;
		for (long t : ring) {
			if (t > since) {
				n++;
			}
		}
		return n;
	}
}
