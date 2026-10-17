package com.flintclient.client.util;

import com.flintclient.client.FlintClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** Versions as the running game reports them, never hard-coded. */
public final class FlintInfo {
	private static String flintVersion;

	private FlintInfo() {
	}

	/** The installed Flint version (this runtime is versioned with the Flint app). */
	public static String flintVersion() {
		if (flintVersion == null) {
			flintVersion = FabricLoader.getInstance()
				.getModContainer(FlintClient.MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		}
		return flintVersion;
	}

	public static String minecraftVersion() {
		return SharedConstants.getCurrentVersion().name();
	}

	/** This runtime only loads under Fabric. */
	public static String loader() {
		return "Fabric";
	}
}
