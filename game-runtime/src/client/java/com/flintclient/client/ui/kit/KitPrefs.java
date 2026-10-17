package com.flintclient.client.ui.kit;

/**
 * The interface preferences the kit follows. The Control Center binds its saved settings through
 * {@link Kit#bind}; until then {@link #DEFAULT} applies (automatic size, normal contrast, animations on and
 * following Minecraft's reduced-motion signal).
 */
public interface KitPrefs {
	/** Fixed values; the default, and handy for tests and previews. */
	record Fixed(int uiScaleDelta, boolean highContrast, boolean animationsEnabled, boolean followMinecraftReducedMotion) implements KitPrefs {
	}

	KitPrefs DEFAULT = new Fixed(0, false, true, true);

	/** Interface size relative to the GUI scale: -1 smaller, 0 automatic, +1 larger (used only when it fits). */
	int uiScaleDelta();

	/** Stronger outlines, lighter secondary text and a thicker focus ring. */
	boolean highContrast();

	/** False switches the kit's transitions off ({@link Kit#reduced()}); Flint's main menu isn't affected. */
	boolean animationsEnabled();

	/** True also switches the kit's transitions off when Minecraft signals reduced motion (panorama speed 0). */
	boolean followMinecraftReducedMotion();
}
