package com.flintclient.client.compat;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The modules Minecraft 1.21.1 can't have, and why (shown on their card and page; they can't be turned on). Kept apart
 * from {@link McCompat}, which makes Minecraft objects as it loads: every module's description asks this as it is built
 * (shared-sources.rules, ModuleDescriptor), and Flint builds them on its settings thread while Minecraft itself is still
 * starting (ControlState.readAhead). Touching Minecraft's classes there let the two threads each wait for a class the other
 * was setting up, and the game never reached its title screen. This class touches nothing of Minecraft's.
 */
public final class ModuleAvailability {
	private static final String NO_POST_PASSES = "Needs the GPU post-processing passes Minecraft added in 1.21.5; 1.21.1 doesn't have them";
	private static final String NO_DEBUG_ENTRIES = "Works on the configurable F3 screen Minecraft added in 1.21.9; 1.21.1's F3 screen is fixed";

	private static final Map<String, String> UNAVAILABLE = Map.ofEntries(
		Map.entry("color_saturation", NO_POST_PASSES),
		Map.entry("custom_f3", NO_DEBUG_ENTRIES));

	private ModuleAvailability() {
	}

	/** Why the module {@code id} can't work on this Minecraft, or null when it can. */
	public static @Nullable String unavailable(String id) {
		return UNAVAILABLE.get(id);
	}
}
