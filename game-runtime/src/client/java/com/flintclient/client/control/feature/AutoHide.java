package com.flintclient.client.control.feature;

/**
 * Autohide HUD's timing: each part of the HUD (health and armor, food and air, hotbar and experience) has a value that
 * changes when it should be seen (a fingerprint the version layer computes each tick). A part hides once nothing has
 * changed for the delay, sliding away over the animation's length, and comes back at once on a change. Together, any
 * part's change brings every part back. Ticks are client ticks; no Minecraft classes.
 */
public final class AutoHide {
	public static final int HEALTH = 0;
	public static final int FOOD = 1;
	public static final int HOTBAR = 2;
	private static final int PARTS = 3;
	private final long[] seen = new long[PARTS];
	/** Ticks since each part last changed. */
	private final int[] still = new int[PARTS];
	private int anyStill;
	private boolean started;

	/** One client tick with the parts' fingerprints now. */
	public void tick(long health, long food, long hotbar) {
		boolean any = part(HEALTH, health) | part(FOOD, food) | part(HOTBAR, hotbar);
		started = true;
		anyStill = any ? 0 : Math.min(anyStill + 1, Integer.MAX_VALUE - 1);
	}

	/** Whether the part changed this tick. */
	private boolean part(int i, long now) {
		if (!started || now != seen[i]) {
			seen[i] = now;
			still[i] = 0;
			return true;
		}
		still[i] = Math.min(still[i] + 1, Integer.MAX_VALUE - 1);
		return false;
	}

	/**
	 * How hidden {@code part} is, 0 (shown) to 1 (gone), {@code partialTick} into the current tick: still for {@code delay}
	 * ticks, then over {@code duration} ticks (0: at once). Eased in and out.
	 */
	public float hidden(int part, boolean separately, int delay, int duration, float partialTick) {
		if (!started) {
			return 0;
		}
		float ticks = (separately ? still[part] : anyStill) + partialTick - delay;
		if (ticks <= 0) {
			return 0;
		}
		if (duration <= 0) {
			return 1;
		}
		float t = Math.min(1F, ticks / duration);
		return t * t * (3 - 2 * t);
	}

	/** A world joined or left: everything shows and starts over. */
	public void reset() {
		started = false;
		anyStill = 0;
		java.util.Arrays.fill(still, 0);
	}
}
