package com.flintclient.client.network.platform;

import com.flintclient.client.network.NetworkCore;
import com.flintclient.client.perf.Compat;
import com.flintclient.client.perf.PerfConfig;
import com.flintclient.client.perf.PerfModule;

/**
 * Developer connection log ({@code dev.network}): with {@code networkCore} running, also one line per phase
 * change of each attempt, with the time since the previous one. No host or address, like the core's own lines.
 */
public final class NetworkLogModule implements PerfModule {
	public static final String ID = "dev.network";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return config.network();
	}

	@Override
	public void start() {
		NetworkCore.logTransitions();
	}
}
