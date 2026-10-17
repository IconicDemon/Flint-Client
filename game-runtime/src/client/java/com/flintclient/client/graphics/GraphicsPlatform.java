package com.flintclient.client.graphics;

import org.jspecify.annotations.Nullable;

/**
 * The version seam for reading Minecraft's graphics device. Each Minecraft family that changes the device
 * API gets its own implementation in {@code graphics/platform}; nothing else in {@code graphics} touches
 * Minecraft's rendering classes.
 */
public interface GraphicsPlatform {
	/** The device as Minecraft created it, or null before it exists. Render thread. */
	@Nullable Device device();

	/**
	 * What Minecraft reports about its graphics device. {@code driver} may be empty, and
	 * {@code maxTextureSize} is 0 when the platform can't tell.
	 */
	record Device(String backend, String name, String vendor, String driver, int maxTextureSize) {
	}
}
