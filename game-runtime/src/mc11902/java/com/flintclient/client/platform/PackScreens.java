package com.flintclient.client.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;

/**
 * 1.19.x: Minecraft's Resource Packs screen takes the parent screen as its first argument, and Options has no
 * updateResourcePacks yet, so Done applies the choice the way 1.19's own Options screen does (see the shared copy).
 */
final class PackScreens {
	private PackScreens() {}

	/** The Resource Packs screen over {@code back}: Done applies the player's choice and returns to {@code back}. */
	static Screen resourcePacks(Minecraft mc, Screen back) {
		return new PackSelectionScreen(back, mc.getResourcePackRepository(), repository -> {
			List<String> before = new ArrayList<>(mc.options.resourcePacks);
			mc.options.resourcePacks.clear();
			mc.options.incompatibleResourcePacks.clear();
			for (Pack pack : repository.getSelectedPacks()) {
				if (!pack.isFixedPosition()) {
					mc.options.resourcePacks.add(pack.getId());
					if (!pack.getCompatibility().isCompatible()) {
						mc.options.incompatibleResourcePacks.add(pack.getId());
					}
				}
			}
			mc.options.save();
			if (!before.equals(mc.options.resourcePacks)) {
				mc.reloadResourcePacks();
			}
			mc.setScreen(back);
		}, mc.getResourcePackDirectory(), Component.translatable("resourcePack.title"));
	}
}
