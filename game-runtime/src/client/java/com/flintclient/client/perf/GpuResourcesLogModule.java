package com.flintclient.client.perf;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.GpuResourceTracker;

/**
 * Developer GPU texture log ({@code dev.gpuResources}): Flint's live textures by kind, with their bytes, at
 * each world join and leave. It only reads; nothing in the game changes.
 */
final class GpuResourcesLogModule implements PerfModule {
	static final String ID = "dev.gpuResources";

	@Override
	public String id() {
		return ID;
	}

	/** Follows the developer flag alone, like the frame-time log. */
	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return config.gpuResources();
	}

	@Override
	public void start() {
	}

	@Override
	public void onWorldJoined() {
		FlintClient.LOGGER.info("Flint GPU textures after joining a world: {}", GpuResourceTracker.get().describe());
	}

	@Override
	public void onWorldLeft() {
		FlintClient.LOGGER.info("Flint GPU textures after leaving a world: {}", GpuResourceTracker.get().describe());
	}
}
