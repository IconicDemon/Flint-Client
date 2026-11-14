package com.flintclient.client.ui.screen;

import com.flintclient.client.mc.Minecraft;
import com.flintclient.client.mc.Screen;

/** The demo's world, as Minecraft 1.8.9's own Play Demo World button starts it (made the first time, opened after). */
final class DemoWorld {
	private static final String LEVEL_ID = "Demo_World";

	private DemoWorld() {
	}

	static void play(Minecraft minecraft, Screen menu) {
		net.minecraft.client.Minecraft.getInstance().startGame(LEVEL_ID, LEVEL_ID, net.minecraft.server.world.DemoServerWorld.SETTINGS);
	}
}
