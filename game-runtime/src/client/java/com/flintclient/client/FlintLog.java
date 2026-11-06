package com.flintclient.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flint's log, for code shared by every Minecraft line: the same logger as {@link FlintClient#LOGGER}, without the 26.x
 * entry point around it. On 1.8.9 (no slf4j) the shared-sources step points it at log4j, which takes the same {@code {}}
 * placeholders and trailing exception.
 */
public final class FlintLog {
	public static final Logger LOGGER = LoggerFactory.getLogger("Flint Client");

	private FlintLog() {
	}
}
