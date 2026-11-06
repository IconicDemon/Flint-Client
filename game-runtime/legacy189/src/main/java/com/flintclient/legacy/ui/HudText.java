package com.flintclient.legacy.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;

/** Text helpers for HUD mods (GUI pixels). */
public final class HudText {
	private HudText() {
	}

	/** {@code text} at half size from ({@code x}, {@code y}). */
	public static void small(String text, float x, float y, int argb, boolean shadow) {
		GlStateManager.pushMatrix();
		GlStateManager.translatef(x, y, 0);
		GlStateManager.scalef(0.5F, 0.5F, 1);
		FastText.draw(text, 0, 0, argb, shadow);
		GlStateManager.popMatrix();
	}
}
