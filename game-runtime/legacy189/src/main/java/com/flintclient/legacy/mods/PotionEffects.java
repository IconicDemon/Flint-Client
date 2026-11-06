package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * Your status effects, as Tiles (newer Minecraft's effect tiles, the time under each) or a List (icon, name, level and time):
 * the 1.8.9 backend of Effects, drawn by the shared view as on every line (it reads the effects through
 * {@code platform.HudEffects}, the icons from 1.8.9's inventory sheet). 1.8.9 has no Status Effect Bars and no effect icons on
 * its HUD to hide, so those settings wait out of sight.
 */
public final class PotionEffects extends HudBackend {
	public PotionEffects() {
		super("effects", "potioneffects", "bar", "hudBars", "inventoryBars", "hideVanillaEffects", "hideInventoryStatus");
	}
}
