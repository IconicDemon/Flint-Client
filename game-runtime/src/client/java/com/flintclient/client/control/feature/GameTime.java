package com.flintclient.client.control.feature;

/**
 * Game ticks as real time. Minecraft counts fuses, cooldowns and effects down one a tick, and a world runs at 20 ticks a
 * second unless a server changed its tick rate (1.20.3's {@code /tick rate}; the client ticks at the same rate). No
 * Minecraft classes.
 */
public final class GameTime {
	/** Minecraft's normal ticks a second. */
	public static final float NORMAL_TICK_RATE = 20;

	private GameTime() {
	}

	/** Seconds {@code ticks} take at {@code tickRate} (a broken or zero rate counts as 20); never negative. */
	public static double seconds(double ticks, float tickRate) {
		float rate = tickRate > 0 && Float.isFinite(tickRate) ? tickRate : NORMAL_TICK_RATE;
		return Math.max(0, ticks) / rate;
	}

	/**
	 * Seconds until a 1.8.9 TNT explodes, from its fuse and the frame's partial tick: 1.8.9 explodes on the tick its fuse is
	 * already 0 ({@code if (fuse-- <= 0)}), one tick after it runs out (81 ticks for a fresh one). Later lines explode as the
	 * fuse reaches 0 ({@code fuse - 1 <= 0}), so theirs is just {@code seconds(fuse - partialTick, rate)}.
	 */
	public static double legacyFuseSeconds(int fuse, float partialTick) {
		return seconds(fuse + 1 - partialTick, NORMAL_TICK_RATE);
	}

	/** The day a world's day time is in, as Minecraft counts it ({@code /time query day}): the first day is day 0. */
	public static long day(long dayTicks) {
		return Math.floorDiv(dayTicks, 24000L);
	}

	/** Minutes past midnight on a clock for a world's day time: tick 0 is 6:00, 6000 noon, 18000 midnight. */
	public static int clockMinutes(long dayTicks) {
		return (int) ((Math.floorMod(dayTicks, 24000L) * 1440 / 24000 + 360) % 1440);
	}
}
