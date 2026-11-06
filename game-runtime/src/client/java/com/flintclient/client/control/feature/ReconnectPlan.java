package com.flintclient.client.control.feature;

/**
 * Reconnect's automatic tries: how many have been made since you were last in a server's world, and how long to wait
 * before the next one: the delay, longer by the increment after each failed try (up to the max delay) when incrementing.
 * A successful join starts the count again. No Minecraft classes.
 */
public final class ReconnectPlan {
	private int made;

	/** Seconds to wait before the next try, or -1 when every try is spent. */
	public double nextDelay(int attempts, double delay, boolean increment, double step, double maxDelay) {
		if (made >= attempts) {
			return -1;
		}
		return increment ? Math.min(delay + step * made, Math.max(delay, maxDelay)) : delay;
	}

	/** A try was started. */
	public void tried() {
		made++;
	}

	public int made() {
		return made;
	}

	/** Joined a world (or gave up by hand): the next disconnect starts from the first try. */
	public void reset() {
		made = 0;
	}
}
