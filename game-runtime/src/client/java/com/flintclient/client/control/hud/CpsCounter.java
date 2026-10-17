package com.flintclient.client.control.hud;

/**
 * Clicks per second for the left and right mouse buttons: the number of presses in the last second.
 * Each button keeps the times of its newest {@link #CAPACITY} presses in a ring, which is more than anyone
 * clicks in a second, so the count is exact in practice and simply tops out at 64 beyond that. Recording
 * and counting allocate nothing, so the HUD can count every frame. Times are milliseconds from one
 * monotonic clock. Render thread only.
 */
public final class CpsCounter {
	public static final int LEFT = 0;
	public static final int RIGHT = 1;
	public static final int CAPACITY = 64;
	public static final long WINDOW_MILLIS = 1000L;
	private static final int MASK = CAPACITY - 1;

	// Both rings in one array: LEFT's at [0, CAPACITY), RIGHT's after it.
	private final long[] times = new long[2 * CAPACITY];
	private final int[] next = new int[2];
	private final int[] size = new int[2];

	/** Records a press of {@code button} ({@link #LEFT} or {@link #RIGHT}) at {@code nowMillis}. */
	public void press(int button, long nowMillis) {
		check(button);
		int slot = next[button];
		times[button * CAPACITY + slot] = nowMillis;
		next[button] = (slot + 1) & MASK;
		if (size[button] < CAPACITY) {
			size[button]++;
		}
	}

	/**
	 * Presses of {@code button} less than {@link #WINDOW_MILLIS} before {@code nowMillis}: one made exactly a
	 * second ago no longer counts. Walks back from the newest press and stops at the first one too old.
	 */
	public int countLastSecond(int button, long nowMillis) {
		check(button);
		int base = button * CAPACITY;
		int slot = next[button];
		int count = 0;
		for (int i = size[button]; i > 0; i--) {
			slot = (slot - 1) & MASK;
			if (nowMillis - times[base + slot] >= WINDOW_MILLIS) {
				break;
			}
			count++;
		}
		return count;
	}

	/** Forgets every press, for example when leaving a world. */
	public void clear() {
		next[LEFT] = 0;
		next[RIGHT] = 0;
		size[LEFT] = 0;
		size[RIGHT] = 0;
	}

	private static void check(int button) {
		if (button != LEFT && button != RIGHT) {
			throw new IllegalArgumentException("Not a CPS button: " + button);
		}
	}
}
