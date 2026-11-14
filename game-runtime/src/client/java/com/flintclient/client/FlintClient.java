package com.flintclient.client;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.perf.PerformanceCore;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;

public final class FlintClient implements ClientModInitializer {
	public static final String MOD_ID = "flintclient";
	public static final String NAME = "Flint Client";
	public static final Logger LOGGER = FlintLog.LOGGER;

	@Override
	public void onInitializeClient() {
		LOGGER.info("{} loaded successfully.", NAME);
		// Instant Swap's standby game steps back before it loads anything (the player's game keeps its frames).
		com.flintclient.client.module.social.InstantSwap.standbyStarting();
		long began = StartupTrace.begin();
		PerformanceCore.init();
		StartupTrace.end("performance core", began);
		try {
			ControlState.init();
		} catch (RuntimeException | LinkageError e) {
			// The Control Center then starts from defaults the first time it's used; the game is unaffected.
			LOGGER.warn("Couldn't read Flint Control Center's settings", e);
		}
		try {
			// Packs and worlds put into the game's folders in a form it can't read (a .rar, a pack a folder down), made readable.
			com.flintclient.client.control.platform.Mc263GameFolders.start(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
		} catch (RuntimeException | LinkageError e) {
			LOGGER.warn("Flint: couldn't start looking after the game's folders", e);
		}
		try {
			// The main menu's partner listings and showcase, asked of the Flint app now, so the menu opens with them.
			com.flintclient.client.module.social.MenuFeeds feeds = com.flintclient.client.module.social.MenuFeeds.get();
			feeds.version(com.flintclient.client.util.FlintInfo.minecraftVersion());
			long now = System.currentTimeMillis();
			feeds.want(com.flintclient.client.module.social.MenuFeeds.Feed.PARTNERS, null, now);
			feeds.want(com.flintclient.client.module.social.MenuFeeds.Feed.SHOWCASE, null, now);
		} catch (RuntimeException | LinkageError e) {
			LOGGER.warn("Flint: couldn't ask the Flint app for the main menu's listings", e);
		}
	}
}
