package com.flintclient.client.control.feature;

/**
 * Your speed for the Momentum module, from where you were at the end of each client tick (20 a second). The speed is the
 * distance moved in one tick times 20, in blocks per second; horizontal ignores up and down. An average covers the last
 * {@value #WINDOW} ticks (half a second). A jump of more than {@value #TELEPORT} blocks in one tick (a teleport, a
 * respawn, an ender pearl) is not movement: it restarts the history. Pure Java, allocation-free.
 */
public final class SpeedTracker {
	/** Ticks the average covers. */
	public static final int WINDOW = 10;
	/** More than this many blocks in one tick is a teleport, not movement. */
	static final double TELEPORT = 12;
	private static final int TICKS_PER_SECOND = 20;

	private final double[] horizontal = new double[WINDOW];
	private final double[] full = new double[WINDOW];
	private int count;
	private int next;
	private boolean have;
	private double lastX;
	private double lastY;
	private double lastZ;

	/** Where you are at the end of a tick. */
	public void sample(double x, double y, double z) {
		if (!have) {
			remember(x, y, z);
			return;
		}
		double dx = x - lastX;
		double dy = y - lastY;
		double dz = z - lastZ;
		double flat = Math.sqrt(dx * dx + dz * dz);
		double all = Math.sqrt(flat * flat + dy * dy);
		remember(x, y, z);
		if (all > TELEPORT) {
			count = 0;
			next = 0;
			return;
		}
		horizontal[next] = flat * TICKS_PER_SECOND;
		full[next] = all * TICKS_PER_SECOND;
		next = (next + 1) % WINDOW;
		count = Math.min(WINDOW, count + 1);
	}

	private void remember(double x, double y, double z) {
		lastX = x;
		lastY = y;
		lastZ = z;
		have = true;
	}

	/** Out of a world (or a new one): forget everything. */
	public void reset() {
		have = false;
		count = 0;
		next = 0;
	}

	/** Whether there's a measured tick yet. */
	public boolean known() {
		return count > 0;
	}

	/** Blocks per second: the last tick, or the average of the window; NaN before the first measured tick. */
	public double speed(boolean groundOnly, boolean average) {
		if (count == 0) {
			return Double.NaN;
		}
		double[] values = groundOnly ? horizontal : full;
		if (!average) {
			return values[(next + WINDOW - 1) % WINDOW];
		}
		double sum = 0;
		for (int i = 0; i < count; i++) {
			sum += values[(next + WINDOW - 1 - i) % WINDOW];
		}
		return sum / count;
	}
}
