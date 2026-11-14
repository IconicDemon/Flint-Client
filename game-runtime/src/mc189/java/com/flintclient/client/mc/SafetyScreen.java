package com.flintclient.client.mc;

/**
 * Newer Minecraft's first-visit multiplayer notice: Minecraft 1.8.9 has none, so it is the server list itself (see
 * {@link Component}; {@code Minecraft.Options.skipMultiplayerWarning} is always true here).
 */
public final class SafetyScreen extends net.minecraft.client.gui.screen.menu.multiplayer.MultiplayerScreen {
	public SafetyScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(parent);
	}
}
