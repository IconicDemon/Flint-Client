package com.flintclient.client.mc;

import org.jspecify.annotations.Nullable;

/** Joining a server through Minecraft's own connection screen, as newer Minecraft names it: Minecraft 1.8.9's (see {@link Component}). */
public final class ConnectScreen {
	private ConnectScreen() {
	}

	public static void startConnecting(net.minecraft.client.gui.screen.Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
		boolean quickPlay, @Nullable Object transfer) {
		net.minecraft.client.Minecraft game = Minecraft.game();
		game.openScreen(new net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen(parent, game, data.entry()));
	}
}
