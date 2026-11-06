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
	}
}
