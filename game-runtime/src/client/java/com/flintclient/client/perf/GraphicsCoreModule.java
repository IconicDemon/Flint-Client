package com.flintclient.client.perf;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.GpuResourceTracker;
import com.flintclient.client.graphics.GraphicsCore;
import com.flintclient.client.graphics.GraphicsInfo;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * {@code graphicsCore}: logs the graphics setup once, at the first screen change (the device exists by
 * then), and when a world is joined with {@code menuResources} running, warns if any of Flint's menu
 * textures are still alive (a leak signal; the menu frees them all on joining). It only reads, so it runs
 * whatever Automatic Optimization says.
 */
final class GraphicsCoreModule implements PerfModule {
	static final String ID = "graphicsCore";

	private boolean described;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return true;
	}

	@Override
	public void start() {
	}

	@Override
	public void onScreenChanged(@Nullable Screen previous, @Nullable Screen next) {
		if (described) {
			return;
		}
		GraphicsInfo info = GraphicsCore.read(PerformanceCore.compat());
		if (info != null) {
			described = true;
			FlintClient.LOGGER.info("Flint Graphics Core: {}", info.describe());
		}
	}

	/** Runs after menuResources (see PerformanceCore.all), so its releases have happened. */
	@Override
	public void onWorldJoined() {
		if (!PerformanceCore.running(MenuResourcesModule.ID)) {
			return;
		}
		String live = GpuResourceTracker.get().describeLive();
		if (!live.isEmpty()) {
			FlintClient.LOGGER.warn("Flint Graphics Core: menu textures still alive in a world: {}", live);
		}
	}
}
