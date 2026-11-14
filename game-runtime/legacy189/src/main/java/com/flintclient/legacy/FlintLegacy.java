package com.flintclient.legacy;

import com.flintclient.legacy.ui.Glyph;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric's entry point: loads Flint's mods and settings, and starts reading the icon font and the main menu's artwork in the background. */
public final class FlintLegacy implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// A standby game of Instant Swap steps back in the system before it loads anything.
		com.flintclient.client.module.social.InstantSwap.standbyStarting();
		Flint.init(FabricLoader.getInstance().getConfigDir());
		Glyph.preload();
		// The main menu's artwork, decoded while the game starts.
		com.flintclient.client.ui.Backdrop.preload();
		GameFolders.start(FabricLoader.getInstance().getGameDir());
		try {
			// The main menu's partner listings and showcase, asked of the Flint app now, so the menu opens with them.
			com.flintclient.client.module.social.MenuFeeds feeds = com.flintclient.client.module.social.MenuFeeds.get();
			feeds.version("1.8.9");
			long now = System.currentTimeMillis();
			feeds.want(com.flintclient.client.module.social.MenuFeeds.Feed.PARTNERS, null, now);
			feeds.want(com.flintclient.client.module.social.MenuFeeds.Feed.SHOWCASE, null, now);
		} catch (RuntimeException | LinkageError e) {
			org.apache.logging.log4j.LogManager.getLogger("Flint").warn("Flint: couldn't ask the Flint app for the main menu's listings", e);
		}
	}
}
