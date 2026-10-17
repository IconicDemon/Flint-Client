package com.flintclient.client.graphics;

import com.flintclient.client.perf.Compat;
import java.util.ArrayList;
import java.util.List;

/**
 * The graphics setup of this run, read once: the backend Minecraft chose ("OpenGL", "Vulkan" or whatever
 * a later version adds), the device, its driver and texture limit, and which rendering mods are loaded.
 */
public record GraphicsInfo(String backend, String device, String vendor, String driver, int maxTextureSize, List<String> optimizers) {
	public GraphicsInfo {
		optimizers = List.copyOf(optimizers);
	}

	public static GraphicsInfo of(GraphicsPlatform.Device device, Compat compat) {
		List<String> optimizers = new ArrayList<>();
		add(optimizers, compat.sodium(), "sodium");
		add(optimizers, compat.immediatelyfast(), "immediatelyfast");
		add(optimizers, compat.iris(), "iris");
		add(optimizers, compat.dynamicFps(), "dynamic_fps");
		return new GraphicsInfo(device.backend(), device.name(), device.vendor(), device.driver(), device.maxTextureSize(), optimizers);
	}

	private static void add(List<String> optimizers, boolean loaded, String id) {
		if (loaded) {
			optimizers.add(id);
		}
	}

	/** For the log, for example "backend OpenGL (4.1 Metal - 91.7), device Apple M2 (Apple), max texture 16384, optimizers sodium". */
	public String describe() {
		StringBuilder line = new StringBuilder("backend ").append(backend);
		if (!driver.isBlank()) {
			line.append(" (").append(driver).append(')');
		}
		line.append(", device ").append(device);
		if (!vendor.isBlank()) {
			line.append(" (").append(vendor).append(')');
		}
		if (maxTextureSize > 0) {
			line.append(", max texture ").append(maxTextureSize);
		}
		line.append(", optimizers ").append(optimizers.isEmpty() ? "none" : String.join(", ", optimizers));
		return line.toString();
	}
}
