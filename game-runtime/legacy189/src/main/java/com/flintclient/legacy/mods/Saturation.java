package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.legacy.module.HudBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.entity.living.player.HungerManager;

/**
 * Your food saturation, which the server sends with every health update: a counter on the HUD (the shared view, as on every
 * line) and an overlay on the hunger bar (each drumstick fills as two points of saturation would, as AppleSkin draws it). The
 * 1.8.9 backend of Saturation.
 */
public final class Saturation extends HudBackend {
	/** How strongly the overlay covers a drumstick. */
	private static final int OVERLAY_ALPHA = 0x8C;
	/** AppleSkin's gold, for an overlay not coloured by the setting. */
	private static final int GOLD = 0xFFC93C;

	public Saturation() {
		// AppleSkin's tooltips, gain and exhaustion overlays, off-hand food and debug lines aren't drawn on 1.8.9 yet.
		super("saturation", "saturation", "tooltipFood", "colorGain", "colorTooltip", "exhaustion", "foodGain", "saturationGain", "offhand",
			"vanillaAnimations", "maxFlashAlpha", "animationSpeed", "healthGain", "naturalRegen", "debugInfo");
	}

	private static HungerManager hunger() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getHungerManager();
	}

	/** Draws the overlay over Minecraft's hunger bar (GUI pixels; right after it draws the status bars). */
	public void drawOverlay(int screenWidth, int screenHeight) {
		HungerManager h = hunger();
		ModuleState s = state();
		if (!enabled() || !s.bool(HudMods.Saturation.OVERLAY) || h == null) {
			return;
		}
		float sat = h.getSaturationLevel();
		if (sat <= 0) {
			return;
		}
		int argb = OVERLAY_ALPHA << 24 | (s.bool(HudMods.Saturation.COLOR_OVERLAY) ? s.rgb(HudMods.Saturation.COLOR) : GOLD);
		int right = screenWidth / 2 + 91;
		int top = screenHeight - 39;
		for (int i = 0; i < 10; i++) {
			float fill = Math.min(1, Math.max(0, (sat - i * 2) / 2));
			if (fill <= 0) {
				break;
			}
			int x = right - i * 8 - 9;
			// The drumstick's inner 7 × 7, filled from its right like the bar empties.
			int w = Math.max(1, Math.round(7 * fill));
			GuiElement.fill(x + 8 - w, top + 1, x + 8, top + 8, argb);
		}
	}
}
