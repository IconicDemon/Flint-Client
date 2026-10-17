package com.flintclient.client.control.feature;

import java.util.Map;

/**
 * Better Sounds' arithmetic: how loud one sound plays, from its category (the first part of its id's path: "entity" in
 * {@code minecraft:entity.zombie.ambient}) and its own setting. Both are percentages of what Minecraft would play, and they
 * multiply; Minecraft's own volume sliders still apply on top. 0 mutes. Pure Java.
 */
public final class SoundMixer {
	/** The categories with a slider, in the reference order; anything else is "other". */
	public static final String[] CATEGORIES = {"item", "entity", "ambient", "music", "ui", "music_disc", "enchant", "weather", "block",
		"particle", "event"};
	public static final int OTHER = CATEGORIES.length;

	private SoundMixer() {
	}

	/** The category index of a sound id's path ("entity.zombie.ambient" → entity), or {@link #OTHER}. */
	public static int category(String path) {
		int dot = path.indexOf('.');
		String head = dot < 0 ? path : path.substring(0, dot);
		for (int i = 0; i < CATEGORIES.length; i++) {
			if (CATEGORIES[i].equals(head)) {
				return i;
			}
		}
		return OTHER;
	}

	/**
	 * The factor (0 to 1) for sound {@code id} (namespaced) given each category's percentage ({@code categories}, one more
	 * than {@link #CATEGORIES} for "other") and the per-sound percentages.
	 */
	public static float factor(String id, int[] categories, Map<String, Integer> perSound) {
		int colon = id.indexOf(':');
		String path = colon < 0 ? id : id.substring(colon + 1);
		float k = categories[category(path)] / 100F;
		Integer own = perSound.get(id);
		if (own != null) {
			k *= own / 100F;
		}
		return Math.max(0, Math.min(1, k));
	}
}
