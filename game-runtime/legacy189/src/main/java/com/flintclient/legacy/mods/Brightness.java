package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;

/**
 * Brightness beyond Minecraft's own slider: the light map is built with this gamma instead of the one in your video settings,
 * which is left as it is (so turning the mod off brings your own setting back, and nothing is written to options.txt). The 1.8.9
 * backend of Lighting: Fullbright lights every cave, Custom Brightness sets the percentage (100% is Minecraft's Bright).
 */
public final class Brightness extends Module {
	/** Fullbright's gamma: the light map at full everywhere. */
	private static final float FULLBRIGHT = 15;
	public final Setting.Choice mode = option(VisualMods.Lighting.MODE);
	public final Setting.Int level = integer(VisualMods.Lighting.BRIGHTNESS);

	public Brightness() {
		super("lighting", "brightness");
	}

	/** The gamma to build the light map with: this mod's while it's on, else Minecraft's own. */
	public float gamma(float own) {
		if (!enabled()) {
			return own;
		}
		return mode.index() == 0 ? FULLBRIGHT : level.value() / 100F;
	}
}
