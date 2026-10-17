package com.flintclient.client.control.feature;

/**
 * Attack Indicator's choice of what to show: what you're doing right now first (sleeping, mining, drawing a bow, raising a
 * shield, eating, a held item cooling down), then your attack. Each source is a progress from 0 to 1, or NaN when it
 * doesn't apply (or its setting is off). Pure Java.
 */
public final class IndicatorChoice {
	public static final int NONE = -1;
	public static final int SLEEP = 0;
	public static final int BREAKING = 1;
	public static final int BOW = 2;
	public static final int SHIELD = 3;
	public static final int CONSUMABLE = 4;
	public static final int ITEM_COOLDOWN = 5;
	public static final int ATTACK = 6;
	public static final int SOURCES = 7;

	private IndicatorChoice() {
	}

	/**
	 * The source to show (the first in order whose progress is a number), or {@link #NONE}. The attack shows only while it
	 * isn't full, unless {@code showFull}.
	 */
	public static int choose(float[] progress, boolean showFull) {
		for (int i = 0; i < ATTACK; i++) {
			if (!Float.isNaN(progress[i])) {
				return i;
			}
		}
		float attack = progress[ATTACK];
		if (Float.isNaN(attack) || attack >= 1 && !showFull) {
			return NONE;
		}
		return ATTACK;
	}

	/** Red at 0 to yellow to green at 1, as {@code 0xRRGGBB}. */
	public static int dynamicColor(float progress) {
		float p = Math.max(0, Math.min(1, progress));
		int r = Math.round(255 * Math.min(1, 2 * (1 - p)));
		int g = Math.round(255 * Math.min(1, 2 * p));
		return r << 16 | g << 8 | 0x30;
	}

	/** Whether a charge that was below full is full now (the moment the cooldown sound plays). */
	public static boolean justCharged(float before, float now) {
		return before < 1 && now >= 1;
	}
}
