package com.flintclient.client.compat.v1190;

import java.util.ArrayList;
import java.util.List;

/**
 * The toasts Minecraft 1.19 (1.19.0) shows, oldest first. 1.19.0 keeps them in five fixed slots, a slot filled again by the next
 * toast waiting (1.19.1 made them a list in the order they came, which Top Down follows): so Toast Control keeps the order itself,
 * as mixin.feature.ToastInstanceMixin makes a toast's instance and as its render says it's gone (mixin.feature.ToastManagerMixin
 * empties it with the slots). Render thread only.
 */
public final class ShowingToasts {
	private static final List<Object> SHOWING = new ArrayList<>();

	private ShowingToasts() {
	}

	public static void shown(Object instance) {
		SHOWING.add(instance);
	}

	public static void gone(Object instance) {
		SHOWING.remove(instance);
	}

	public static void cleared() {
		SHOWING.clear();
	}

	/** How many toasts showing came after {@code instance} (0 for the newest, or one not known). */
	public static int newer(Object instance) {
		int at = SHOWING.indexOf(instance);
		return at < 0 ? 0 : SHOWING.size() - 1 - at;
	}
}
