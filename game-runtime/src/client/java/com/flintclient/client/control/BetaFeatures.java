package com.flintclient.client.control;

/**
 * Which of Flint's unfinished features this build exposes. Version-neutral; read once per use, never per frame in a
 * way that matters.
 *
 * <p>For the first public beta the Right Shift customization (the HUD editor, its hub and widget palette, and the
 * module, settings, profiles, presets and themes window) is switched off: its key bindings are not registered with
 * Minecraft (so Right Shift stays an ordinary key and nothing of Flint's appears under Controls), every way of opening
 * it refuses, and the first-world "Press Right Shift" hint isn't shown. Its code stays in place and is compiled and
 * tested as before. To bring it back, set {@link #RIGHT_SHIFT_CUSTOMIZATION} to true; nothing else needs restoring.
 * HUD modules a profile already has on keep drawing either way.
 */
public final class BetaFeatures {
	/** The Right Shift customization: off for the first beta. */
	public static final boolean RIGHT_SHIFT_CUSTOMIZATION = false;

	private BetaFeatures() {
	}
}
