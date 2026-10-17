package com.flintclient.client.graphics;

import com.flintclient.client.graphics.platform.Mc263GraphicsPlatform;
import com.flintclient.client.perf.Compat;
import org.jspecify.annotations.Nullable;

/**
 * Flint Graphics Core: what this run renders with. The one place that picks the {@link GraphicsPlatform}
 * for this Minecraft version. Render thread only.
 */
public final class GraphicsCore {
	private static final GraphicsPlatform PLATFORM = new Mc263GraphicsPlatform();
	private static @Nullable GraphicsInfo info;

	private GraphicsCore() {
	}

	/** Reads the graphics setup the first time it's asked once the device exists; null before that. */
	public static @Nullable GraphicsInfo read(Compat compat) {
		if (info == null) {
			GraphicsPlatform.Device device = PLATFORM.device();
			if (device != null) {
				info = GraphicsInfo.of(device, compat);
			}
		}
		return info;
	}

	/** As read so far, or null. */
	public static @Nullable GraphicsInfo info() {
		return info;
	}
}
