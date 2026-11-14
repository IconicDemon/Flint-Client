package com.flintclient.client.compat.misc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import org.jspecify.annotations.Nullable;

/**
 * Flint's own files (its jar, or the resources folder in a development run) as Minecraft 1.19.2's built-in pack, for
 * {@code BuiltInResources} when Fabric API's resource loader isn't there. 1.19.2 opens packs only from a folder or a zip file
 * on disk (PathPackResources came in 1.19.3), and Fabric hands Flint's files over as paths inside its jar, so this reads them
 * through those paths. As on the other versions the pack is required, fixed at the top of the list (options.txt leaves fixed
 * packs out), and its description and format come from code: the jar carries no pack.mcmeta, and the format is always the
 * running game's.
 */
public final class BuiltInPack implements PackResources {
	private static @Nullable PackMetadataSection metadata;
	private final String name;
	private final Path root;

	private BuiltInPack(String name, Path root) {
		this.name = name;
		this.root = root;
	}

	/** The pack {@code id}, titled {@code title}, over the mod root {@code root} (the folder that holds assets/). */
	public static Pack create(String id, Component title, Path root) {
		PackMetadataSection meta = metadata();
		return new Pack(id, true, () -> new BuiltInPack(id, root), title, meta.getDescription(),
			PackCompatibility.forMetadata(meta, PackType.CLIENT_RESOURCES), Pack.Position.TOP, true, PackSource.BUILT_IN);
	}

	private static PackMetadataSection metadata() {
		PackMetadataSection meta = metadata;
		if (meta == null) {
			meta = new PackMetadataSection(Component.literal("Flint Client's built-in assets"),
				PackType.CLIENT_RESOURCES.getVersion(SharedConstants.getCurrentVersion()));
			metadata = meta;
		}
		return meta;
	}

	/** A file under the pack's root, or null when there is none (or the name tries to leave the root). */
	private @Nullable Path file(String relative) {
		if (relative.contains("..") || relative.contains("\\")) {
			return null;
		}
		Path path = root.resolve(relative);
		return Files.isRegularFile(path) ? path : null;
	}

	private static String path(PackType type, ResourceLocation location) {
		return type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
	}

	@Override
	public InputStream getRootResource(String fileName) throws IOException {
		if (fileName.contains("/") || fileName.contains("\\")) {
			throw new IllegalArgumentException("Root resources can only be filenames, not paths (no / allowed!)");
		}
		Path path = file(fileName);
		if (path == null) {
			throw new FileNotFoundException(fileName);
		}
		return Files.newInputStream(path);
	}

	@Override
	public InputStream getResource(PackType type, ResourceLocation location) throws IOException {
		Path path = file(path(type, location));
		if (path == null) {
			throw new FileNotFoundException(path(type, location));
		}
		return Files.newInputStream(path);
	}

	@Override
	public boolean hasResource(PackType type, ResourceLocation location) {
		return file(path(type, location)) != null;
	}

	/** Every file under {@code namespace}'s {@code folder} that the filter takes (metadata files aside), as Minecraft's folder packs list them. */
	@Override
	public Collection<ResourceLocation> getResources(PackType type, String namespace, String folder, Predicate<ResourceLocation> filter) {
		List<ResourceLocation> found = new ArrayList<>();
		if (folder.contains("..")) {
			return found;
		}
		Path base = root.resolve(type.getDirectory()).resolve(namespace);
		Path start = base.resolve(folder);
		if (!Files.isDirectory(start)) {
			return found;
		}
		try (Stream<Path> files = Files.walk(start)) {
			files.filter(Files::isRegularFile).forEach(file -> {
				if (file.getFileName().toString().endsWith(".mcmeta")) {
					return;
				}
				StringBuilder relative = new StringBuilder();
				for (Path part : base.relativize(file)) {
					if (relative.length() > 0) {
						relative.append('/');
					}
					relative.append(part);
				}
				ResourceLocation location = ResourceLocation.tryBuild(namespace, relative.toString());
				if (location != null && filter.test(location)) {
					found.add(location);
				}
			});
		} catch (IOException | RuntimeException e) {
			return found;
		}
		return found;
	}

	@Override
	public Set<String> getNamespaces(PackType type) {
		Set<String> namespaces = new HashSet<>();
		Path folder = root.resolve(type.getDirectory());
		if (!Files.isDirectory(folder)) {
			return namespaces;
		}
		try (Stream<Path> entries = Files.list(folder)) {
			entries.filter(Files::isDirectory).forEach(dir -> {
				String namespace = dir.getFileName().toString();
				// A zip's folder names end with its separator.
				if (namespace.endsWith("/")) {
					namespace = namespace.substring(0, namespace.length() - 1);
				}
				if (namespace.equals(namespace.toLowerCase(Locale.ROOT)) && ResourceLocation.tryBuild(namespace, "x") != null) {
					namespaces.add(namespace);
				}
			});
		} catch (IOException | RuntimeException e) {
			return namespaces;
		}
		return namespaces;
	}

	/** Only the pack section, answered from code (there is no pack.mcmeta). */
	@Override
	@SuppressWarnings("unchecked")
	public <T> @Nullable T getMetadataSection(MetadataSectionSerializer<T> serializer) {
		return serializer == PackMetadataSection.SERIALIZER ? (T) metadata() : null;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void close() {
	}
}
