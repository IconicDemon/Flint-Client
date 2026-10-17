package com.flintclient.client.perf;

import com.flintclient.client.module.screenshots.ScreenshotLibrary;

/**
 * {@code decodeExecutor}: screenshot thumbnails are decoded on one low-priority Flint thread instead of
 * Minecraft's shared worker pool, which also builds chunk meshes. When off, they go to the shared pool as
 * before; decodes no longer wanted are skipped either way.
 */
final class DecodeExecutorModule implements PerfModule {
	static final String ID = "decodeExecutor";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return config.automaticOptimization();
	}

	@Override
	public void start() {
		ScreenshotLibrary.useOwnDecoder(true);
	}
}
