package com.flintclient.client.perf;

import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.ui.Backdrop;

/**
 * {@code menuResources}: Flint's menu holds a shape atlas (a texture plus its image) and the 1600×900
 * menu artwork, which nothing draws while a world is played. They're released when a world is joined
 * and made again the next time a Flint screen draws. When the world is left, the artwork starts
 * decoding on a worker thread so the menu doesn't have to wait for it.
 */
final class MenuResourcesModule implements PerfModule {
	static final String ID = "menuResources";

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
	}

	@Override
	public void onWorldJoined() {
		MaskAtlas.get().release();
		Backdrop.release();
	}

	@Override
	public void onWorldLeft() {
		Backdrop.preload();
	}
}
