package com.flintclient.client.cosmetics;

import com.flintclient.client.FlintLog;
import com.flintclient.client.cosmetics.ModelItem.FileRef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Local test items for Flint's developers and QA, so every kind can be tried before the Store serves real ones: only when the
 * game is started with {@code -Dflint.cosmetics.dev=FOLDER}, never otherwise. Each subfolder of FOLDER is one item:
 *
 * <pre>
 * item.json        {"kind": "wings", "attach": {"anchor": "anchor_body", "side": "left"}, "texture_frames": 4, "frame_ms": 100,
 *                   "hides_cape": false, "wearers": "self" | "all"}
 * *.geo.json       Bedrock geometry (or geometry.json)
 * *.png            the texture (a vertical strip of texture_frames frames)
 * *.animation.json Bedrock animations (optional; an emote's only file)
 * </pre>
 *
 * Read with the Store's limits and the same loader (files checked by their own SHA-256). {@code wearers}: on you only (the
 * default), or on every player in view too (FPS checks with many wearers). The first item of a kind per slot wins. Pure Java.
 */
public final class DevCosmetics {
	public static final String PROPERTY = "flint.cosmetics.dev";

	/** One item: {@code everyone}: shown on every player in view, not only you. */
	public record DevItem(ModelItem item, boolean everyone, String folder) {
	}

	private DevCosmetics() {
	}

	/** The folder named by {@link #PROPERTY}, or null (the normal case). */
	public static @Nullable Path root() {
		String dir = System.getProperty(PROPERTY);
		if (dir == null || dir.isBlank()) {
			return null;
		}
		Path p = Path.of(dir).toAbsolutePath().normalize();
		return Files.isDirectory(p) ? p : null;
	}

	/** The items in {@code root}, each subfolder read (one that isn't an item is skipped with a line in the log). */
	public static List<DevItem> read(Path root) {
		List<DevItem> out = new ArrayList<>();
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
				try {
					DevItem item = item(dir);
					if (item != null) {
						out.add(item);
					}
				} catch (IOException | RuntimeException e) {
					FlintLog.LOGGER.warn("Flint 3D cosmetics: dev item {} skipped: {}", dir.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			FlintLog.LOGGER.warn("Flint 3D cosmetics: dev items folder unreadable: {}", e.toString());
		}
		return out;
	}

	static @Nullable DevItem item(Path dir) throws IOException {
		Path json = dir.resolve("item.json");
		if (!Files.isRegularFile(json) || Files.size(json) > 16 * 1024) {
			return null;
		}
		JsonElement parsed = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8));
		if (!parsed.isJsonObject()) {
			return null;
		}
		JsonObject o = parsed.getAsJsonObject();
		CosmeticKind kind = CosmeticKind.of(Json.string(o, "kind"));
		if (kind == null) {
			FlintLog.LOGGER.warn("Flint 3D cosmetics: dev item {}: unknown kind", dir.getFileName());
			return null;
		}
		FileRef geometry = find(dir, ".geo.json", "geometry.json");
		FileRef texture = find(dir, ".png", null);
		FileRef animation = find(dir, ".animation.json", "animation.json");
		Long frames = Json.integer(o, "texture_frames", 1, CosmeticLimits.MAX_FRAMES);
		int n = frames == null ? 1 : frames.intValue();
		int[] ms = ModelItem.frameMs(o.get("frame_ms"), n);
		if (ms == null) {
			FlintLog.LOGGER.warn("Flint 3D cosmetics: dev item {}: frame_ms out of the limits", dir.getFileName());
			return null;
		}
		JsonObject attach = Json.object(o, "attach");
		int anchor = CosmeticKind.anchor(Json.string(attach, "anchor"));
		boolean mirror = kind == CosmeticKind.PET && "left".equals(Json.string(attach, "side"));
		String id = "dev-" + dir.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
		if (kind == CosmeticKind.EMOTE ? animation == null : texture == null || geometry == null && kind != CosmeticKind.SHIELD) {
			FlintLog.LOGGER.warn("Flint 3D cosmetics: dev item {}: files missing for a {}", dir.getFileName(), kind.id);
			return null;
		}
		ModelItem item = kind == CosmeticKind.EMOTE
			? new ModelItem(id, kind, null, null, animation, 1, new int[0], CosmeticModel.BODY, false, false)
			: new ModelItem(id, kind, geometry, texture, animation, n, ms, anchor < 0 ? kind.defaultAnchor : anchor, mirror,
				kind == CosmeticKind.WINGS && Json.bool(o, "hides_cape"));
		return new DevItem(item, "all".equals(Json.string(o, "wearers")), dir.getFileName().toString());
	}

	/** The file in {@code dir} ending in {@code suffix} (or named {@code name}), as a reference with its own hash. */
	private static @Nullable FileRef find(Path dir, String suffix, @Nullable String name) throws IOException {
		Path found = null;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.sorted().toList()) {
				String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
				if (n.equals(name) || n.endsWith(suffix) && !(suffix.equals(".png") && n.startsWith("thumb"))) {
					found = f;
					break;
				}
			}
		}
		if (found == null) {
			return null;
		}
		long size = Files.size(found);
		if (size < 1 || size > CosmeticLimits.MAX_TEXTURE_BYTES) {
			return null;
		}
		byte[] bytes = Files.readAllBytes(found);
		return new FileRef(found.toUri(), ModelCosmetics.sha256(bytes), bytes.length);
	}
}
