package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;

/**
 * How nametags are drawn: your own in third person, a shadow under the text, and how dark the plate behind it is (or none).
 * Minecraft still decides which nametags show (teams, sneaking, distance); Flint only changes how they look. The 1.8.9 backend of
 * Nametags.
 */
public final class Nametags extends Module {
	public final Setting.Bool own = bool(VisualMods.Nametags.OWN);
	public final Setting.Bool shadow = bool(VisualMods.Nametags.SHADOW);
	public final Setting.Bool plate = bool(VisualMods.Nametags.BACKGROUND);
	public final Setting.Int opacity = integer(VisualMods.Nametags.BACKGROUND_OPACITY);

	public Nametags() {
		super("nametags", "nametags");
	}

	/** The plate's alpha for Minecraft's {@code original}. */
	public float plateAlpha(float original) {
		if (!enabled()) {
			return original;
		}
		return plate.on() ? opacity.value() / 100F : 0;
	}
}
