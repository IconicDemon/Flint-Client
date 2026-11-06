package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * How far away your last hit landed, from your eyes to the point your crosshair met the target (Combat measures it): the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Reach extends HudBackend {
	public Reach() {
		// The entity filter needs the kind of what you hit, which 1.8.9's reach measure doesn't keep.
		super("reach_display", "reachdisplay", "enableEntityFilter", "filterMode", "whitelist", "blacklist");
	}
}
