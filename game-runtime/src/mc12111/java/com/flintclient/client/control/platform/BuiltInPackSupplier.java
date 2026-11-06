package com.flintclient.client.control.platform;

import java.nio.file.Path;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

/** Opens Flint's jar (or folder) as a pack, for {@link BuiltInResources}: Minecraft 1.21.11 (as 26.1) asks for it as one primary pack. */
record BuiltInPackSupplier(Path root) implements Pack.ResourcesSupplier {
	@Override
	public PackResources openPrimary(PackLocationInfo location) {
		return new BuiltInResources.Resources(location, root);
	}

	@Override
	public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
		return new BuiltInResources.Resources(location, root);
	}
}
