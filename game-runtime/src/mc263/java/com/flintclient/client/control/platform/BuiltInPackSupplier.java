package com.flintclient.client.control.platform;

import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

/** Opens Flint's jar (or folder) as a pack, for {@link BuiltInResources}: Minecraft 26.3 asks for its metadata, then its resources. */
record BuiltInPackSupplier(Path root) implements Pack.ResourcesSupplier {
	@Override
	public PackMetadataResources openMetadata(PackLocationInfo location) {
		return new BuiltInResources.Resources(location, root);
	}

	@Override
	public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
		return Stream.of(new BuiltInResources.Resources(location, root));
	}
}
