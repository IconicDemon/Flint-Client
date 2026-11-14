package com.flintclient.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Flint's log and mod id for the shared code Minecraft 1.8.9 adapts (Flint's main menu). 1.8.9's entry point is
 * {@code com.flintclient.legacy.FlintLegacy}; the 26.x one is the shared FlintClient, which 1.8.9 doesn't compile.
 */
public final class FlintClient {
	public static final String MOD_ID = "flintclient";
	public static final Logger LOGGER = LogManager.getLogger("Flint Client");

	private FlintClient() {
	}
}
