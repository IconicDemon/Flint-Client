package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.jspecify.annotations.Nullable;

/**
 * Makes Flint's own assets (fonts, menu artwork, translations) load when Fabric API isn't installed.
 * Minecraft only reads assets from resource packs and Fabric Loader adds none for mods; Fabric API's
 * resource loader is what normally turns each mod into one. Without it, this adds a built-in client pack
 * for Flint's jar: required (always on), fixed at the top of the list, and written nowhere (options.txt
 * leaves fixed packs out). With Fabric's resource loader present it does nothing at all, so Flint loads
 * exactly as before.
 *
 * <p>The pack's description and format come from code, as for Minecraft's own built-in pack, so the jar
 * carries no pack.mcmeta that Fabric would then read for its own pack; the format is always the running
 * game's, so the pack is never flagged as made for another version.
 *
 * <p>This runs inside Minecraft's resource pack scan, which the game's start-up depends on, so a failure
 * here is logged once and leaves the pack out for the session rather than stopping the game.
 */
public final class BuiltInResources {
	static final String PACK_ID = FlintClient.MOD_ID;
	/** Fabric API's resource loader: v1 makes the mod packs, v0 is the older name kept for compatibility. */
	private static final String[] FABRIC_RESOURCE_LOADERS = {"fabric-resource-loader-v0", "fabric-resource-loader-v1"};
	private static final PackSelectionConfig SELECTION = new PackSelectionConfig(true, Pack.Position.TOP, true);
	private static final HookGuard GUARD = new HookGuard("resources", BuiltInResources::turnedOff);
	private static @Nullable Boolean needed;
	private static boolean warned;

	private BuiltInResources() {
	}

	/**
	 * From the end of {@code ClientPackSource.populatePackList}: offers Flint's pack alongside Minecraft's
	 * built-in ones, unless Fabric's resource loader already provides it. Runs on every resource pack scan,
	 * which only happens on (re)loads.
	 */
	public static void offer(BiConsumer<String, Function<String, @Nullable Pack>> discovered) {
		if (!GUARD.on()) {
			return;
		}
		try {
			if (needed == null) {
				needed = needsPack(FabricLoader.getInstance()::isModLoaded);
				if (needed) {
					FlintClient.LOGGER.info("Fabric API's resource loader isn't installed; Flint's assets load as a built-in resource pack");
				}
			}
			if (!needed) {
				return;
			}
			Path root = assetRoot();
			if (root == null) {
				if (!warned) {
					warned = true;
					FlintClient.LOGGER.warn("Couldn't find Flint's assets in its mod files; Flint's fonts and text may be missing");
				}
				return;
			}
			discovered.accept(PACK_ID, id -> create(id, root));
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	/**
	 * Whether Flint has to add its own pack: only when none of Fabric API's resource loader modules is
	 * installed, as any of them already makes every mod a pack. Asks {@code isModLoaded} about mod ids.
	 */
	static boolean needsPack(Predicate<String> isModLoaded) {
		for (String id : FABRIC_RESOURCE_LOADERS) {
			if (isModLoaded.test(id)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The mod root that holds Flint's assets. The released jar has one root; a development run has one
	 * per build output folder, and only the resources folder has assets.
	 */
	private static @Nullable Path assetRoot() {
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(FlintClient.MOD_ID);
		if (mod.isEmpty()) {
			return null;
		}
		for (Path root : mod.get().getRootPaths()) {
			if (Files.isDirectory(root.resolve(PackType.CLIENT_RESOURCES.getDirectory()).resolve(FlintClient.MOD_ID))) {
				return root;
			}
		}
		return null;
	}

	// Called by Minecraft straight after the scan; a null pack is left out (BuiltInPackSource.listBundledPacks).
	private static @Nullable Pack create(String id, Path root) {
		if (!GUARD.on()) {
			return null;
		}
		try {
			PackLocationInfo location = new PackLocationInfo(id, Component.literal(FlintClient.NAME), PackSource.BUILT_IN, Optional.empty());
			return Pack.readMetaAndCreate(location, new JarPack(root), PackType.CLIENT_RESOURCES, SELECTION);
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return null;
		}
	}

	private static void turnedOff(String path, RuntimeException error) {
		FlintClient.LOGGER.warn("Flint's built-in resource pack couldn't be added and is off for this session; Flint's fonts and text may be missing", error);
	}

	/** Opens the jar (or folder) as a pack. */
	private record JarPack(Path root) implements Pack.ResourcesSupplier {
		@Override
		public PackMetadataResources openMetadata(PackLocationInfo location) {
			return new Resources(location, root);
		}

		@Override
		public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
			return Stream.of(new Resources(location, root));
		}
	}

	/** The jar's files, with the pack's metadata answered from code instead of a pack.mcmeta file. */
	private static final class Resources extends PathPackResources {
		private static @Nullable ResourceMetadata metadata;

		Resources(PackLocationInfo location, Path root) {
			super(location, root);
		}

		@Override
		public <T> @Nullable T getMetadataSection(MetadataSectionType<T> type) {
			if (metadata == null) {
				PackMetadataSection pack = new PackMetadataSection(Component.literal("Flint Client's built-in assets"),
					SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).minorRange());
				metadata = ResourceMetadata.of(PackMetadataSection.CLIENT_TYPE, pack);
			}
			return metadata.getSection(type).orElse(null);
		}
	}
}
