package com.flintclient.client.perf;

import com.flintclient.client.render.MaskAtlas;

/**
 * {@code atlasBatching}: the menu's shape atlas finds shapes without allocating, writes new shapes into
 * free space and uploads them once per frame instead of rebuilding the whole texture for each one, and
 * keeps variable-size shapes (tooltips, cards) from piling up. Every frame draws the same pixels; when
 * off, the atlas works as it always did.
 */
final class AtlasBatchingModule implements PerfModule {
	static final String ID = "atlasBatching";

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
		MaskAtlas.useBatching(true);
	}
}
