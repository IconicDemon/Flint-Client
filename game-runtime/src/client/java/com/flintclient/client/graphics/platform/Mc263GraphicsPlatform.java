package com.flintclient.client.graphics.platform;

import com.flintclient.client.graphics.GraphicsPlatform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3, whose device API is {@code com.mojang.renderpearl} (26.2 and older keep it under
 * {@code com.mojang.blaze3d}). The device exists once {@code RenderSystem.initRenderer} has run, early in
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
