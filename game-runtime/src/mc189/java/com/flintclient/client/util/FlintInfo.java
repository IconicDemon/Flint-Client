package com.flintclient.client.util;

import com.flintclient.client.FlintClient;
import net.fabricmc.loader.api.FabricLoader;

/** Versions as the running game reports them, on Minecraft 1.8.9 (the main menu's version line, What's new). */
public final class FlintInfo {
	private static String flintVersion;
	private static String minecraftVersion;

	private FlintInfo() {
	}

	/** The installed Flint version (this runtime is versioned with the Flint app, plus "+1.8.9"). */
	public static String flintVersion() {
		if (flintVersion == null) {
			String full = FabricLoader.getInstance()
				.getModContainer(FlintClient.MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
			int plus = full.indexOf('+');
			flintVersion = plus > 0 ? full.substring(0, plus) : full;
		}
		return flintVersion;
	}

	public static String minecraftVersion() {
		if (minecraftVersion == null) {
			minecraftVersion = FabricLoader.getInstance()
				.getModContainer("minecraft")
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("1.8.9");
		}
		return minecraftVersion;
	}

	/** This runtime only loads under Fabric. */
	public static String loader() {
		return "Fabric";
	}
}
