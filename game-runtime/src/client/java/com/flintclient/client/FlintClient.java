package com.flintclient.client;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.perf.PerformanceCore;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FlintClient implements ClientModInitializer {
	public static final String MOD_ID = "flintclient";
	public static final String NAME = "Flint Client";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	@Override
	public void onInitializeClient() {
		LOGGER.info("{} loaded successfully.", NAME);
		PerformanceCore.init();
		try {
			ControlState.init();
		} catch (RuntimeException e) {
			// The Control Center then starts from defaults the first time it's used; the game is unaffected.
			LOGGER.warn("Couldn't read Flint Control Center's settings", e);
		}
	}
}
