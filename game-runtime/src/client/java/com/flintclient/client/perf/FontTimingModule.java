package com.flintclient.client.perf;

import com.flintclient.client.render.FontPreload;

/**
 * Developer font timing log ({@code dev.fontTiming}): one line per Flint font set made, with the
 * nanoseconds it took on the render thread and whether a worker had prepared it. One short launch with
 * {@code fontPreload} on and one with it off show the difference; no benchmark session needed. It only
 * measures.
 */
final class FontTimingModule implements PerfModule {
	static final String ID = "dev.fontTiming";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return config.fontTiming();
	}

	@Override
	public void start() {
		FontPreload.logTimings();
	}
}
