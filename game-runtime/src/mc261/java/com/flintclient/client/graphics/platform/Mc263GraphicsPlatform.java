package com.flintclient.client.graphics.platform;

import com.flintclient.client.graphics.GraphicsPlatform;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.1, whose device answers each question with its own getter (26.2 groups them in a {@code DeviceInfo}).
 * The device exists once {@code RenderSystem.initRenderer} has run, early in Minecraft's start-up and before the first
 * screen is set.
 */
public final class Mc263GraphicsPlatform implements GraphicsPlatform {
	@Override
	public @Nullable Device device() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}
		return new Device(text(device.getBackendName()), text(device.getRenderer()), text(device.getVendor()), text(device.getVersion()),
			device.getMaxTextureSize());
	}

	// The OpenGL strings come straight from the driver (glGetString), which may return null.
	private static String text(@Nullable String value) {
		return value == null ? "" : value.strip();
	}
}
