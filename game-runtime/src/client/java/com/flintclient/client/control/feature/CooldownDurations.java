package com.flintclient.client.control.feature;

import java.util.HashMap;
import java.util.Map;

/**
 * The full length of each item cooldown the server started, by cooldown group, so the Cooldowns module can show
 * seconds left (Minecraft keeps only the share left visible). Fed by the version layer when a cooldown starts or ends.
 * Render thread.
 */
public final class CooldownDurations {
	private static final Map<String, Integer> TICKS = new HashMap<>();

	private CooldownDurations() {
	}

	public static void started(String group, int ticks) {
		TICKS.put(group, ticks);
	}

	public static void ended(String group) {
		TICKS.remove(group);
	}

	/** The cooldown's full length in ticks, or 0 when unknown. */
	public static int ticks(String group) {
		Integer ticks = TICKS.get(group);
		return ticks == null ? 0 : ticks;
	}
}
