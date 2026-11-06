package com.flintclient.legacy;

import com.flintclient.legacy.ui.Glyph;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric's entry point: loads Flint's mods and settings, and starts reading the icon font in the background. */
public final class FlintLegacy implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Flint.init(FabricLoader.getInstance().getConfigDir());
		Glyph.preload();
		GameFolders.start(FabricLoader.getInstance().getGameDir());
	}
}
