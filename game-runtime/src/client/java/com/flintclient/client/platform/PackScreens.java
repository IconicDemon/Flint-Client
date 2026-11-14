package com.flintclient.client.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;

/**
 * Minecraft's Resource Packs screen, made the way this Minecraft line's constructor wants it. 1.19.x takes the parent
 * screen first, so that line has its own copy of this file (src/mc11902/java/.../platform/PackScreens.java).
 */
final class PackScreens {
	private PackScreens() {}

	/** The Resource Packs screen over {@code back}: Done applies the player's choice and returns to {@code back}. */
	static Screen resourcePacks(Minecraft mc, Screen back) {
		return new PackSelectionScreen(mc.getResourcePackRepository(), repository -> {
			mc.options.updateResourcePacks(repository);
			mc.gui.setScreen(back);
		}, mc.getResourcePackDirectory(), Component.translatable("resourcePack.title"));
	}
}
