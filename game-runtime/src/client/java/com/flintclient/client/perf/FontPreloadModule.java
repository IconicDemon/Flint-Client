package com.flintclient.client.perf;

import com.flintclient.client.render.FontPreload;

/**
 * {@code fontPreload}: Flint's font files are read and their glyphs loaded on Minecraft's worker threads
 * after each resource reload and GUI scale change, instead of all at once on the render thread the first
 * time each text role is drawn. Measured once each way on one machine (docs/STATUS.md, 2026-10-15): the first
 * menu's render-thread font time 22.8 ms → 1.2 ms; a single launch, so an indication, not a benchmark. {@code
 * dev.fontTiming} logs the render-thread time per font set. When off, fonts load as they always did.
 */
final class FontPreloadModule implements PerfModule {
	static final String ID = "fontPreload";

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
		FontPreload.enable();
	}
}
