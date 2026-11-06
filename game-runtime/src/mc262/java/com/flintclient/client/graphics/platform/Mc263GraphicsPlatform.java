package com.flintclient.client.graphics.platform;

import com.flintclient.client.graphics.GraphicsPlatform;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.2, whose device API is still under {@code com.mojang.blaze3d} (26.3 moved it to
 * {@code com.mojang.renderpearl}). The device exists once {@code RenderSystem.initRenderer} has run, early in
 * Minecraft's start-up and before the first screen is set.
 */
public final class Mc263GraphicsPlatform implements GraphicsPlatform {
	@Override
	public @Nullable Device device() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}
		DeviceInfo info = device.getDeviceInfo();
		return new Device(text(info.backendName()), text(info.name()), text(info.vendorName()), text(info.driverInfo()),
			info.limits().maxTextureSize());
	}

	// The OpenGL strings come straight from the driver (glGetString), which may return null.
	private static String text(@Nullable String value) {
		return value == null ? "" : value.strip();
	}
}
