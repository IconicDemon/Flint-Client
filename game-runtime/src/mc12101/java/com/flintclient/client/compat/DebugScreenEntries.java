package com.flintclient.client.compat;

import net.minecraft.resources.ResourceLocation;

/** The F3 entries Custom F3 names (1.21.9's {@code DebugScreenEntries}); 1.21.1's F3 screen is fixed, so these are names only. */
public final class DebugScreenEntries {
	/** The 3D crosshair F3 shows in place of the crosshair. */
	public static final ResourceLocation THREE_DIMENSIONAL_CROSSHAIR = ResourceLocation.withDefaultNamespace("3d_crosshair");

	private DebugScreenEntries() {
	}
}
