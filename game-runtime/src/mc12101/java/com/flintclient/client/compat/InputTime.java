package com.flintclient.client.compat;

import net.minecraft.Util;

/** When the player last pressed a key or a button, scrolled or moved the mouse (1.21.2's FramerateLimitTracker keeps this). */
public final class InputTime {
	private static volatile long latest = Util.getMillis();

	private InputTime() {
	}

	public static void now() {
		latest = Util.getMillis();
	}

	public static long latest() {
		return latest;
	}
}
