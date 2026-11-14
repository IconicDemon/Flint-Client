package com.flintclient.client.mc;

import org.lwjgl.Sys;

/**
 * Minecraft's credits and attribution, as newer Minecraft names the screen: Minecraft 1.8.9 has only its end credits (which
 * respawn a player at the end), so it is Mojang's attribution page, after Minecraft's own link confirmation (see {@link Component}).
 */
public final class CreditsAndAttributionScreen extends net.minecraft.client.gui.screen.game.ConfirmChatLinkScreen {
	private static final String ATTRIBUTION = "https://www.minecraft.net/en-us/attribution";
	private static final int ID = 31_102_010;

	public CreditsAndAttributionScreen(net.minecraft.client.gui.screen.Screen parent) {
		super((open, id) -> {
			if (open) {
				Sys.openURL(ATTRIBUTION);
			}
			Minecraft.game().openScreen(parent);
		}, ATTRIBUTION, ID, true);
		noWarning();
	}
}
