package com.flintclient.client.cosmetics;

import com.flintclient.client.cosmetics.CosmeticLimits.Refused;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A cosmetic's model: Bedrock entity geometry ({@code minecraft:geometry}, format 1.12 and later, as Blockbench saves a
 * "Bedrock Entity"), read within {@link CosmeticLimits}. Coordinates stay Bedrock's (pixels, 16 to a block, y up, the player's
 * feet at 0, body x -4..4, y 12..24, z -2..2, the back at +z); {@link CosmeticModel} turns them into Minecraft's model space.
 * Bones come parents first. What Flint doesn't draw is read and dropped (locators, materials, {@code binding},
 * {@code poly_mesh}). Pure Java.
 */
public record CosmeticGeometry(int textureWidth, int textureHeight, List<Bone> bones, int cubeCount) {
	/** Faces in the order {@link Cube#faces} keeps them. */
	public static final int NORTH = 0;
	public static final int EAST = 1;
	public static final int SOUTH = 2;
	public static final int WEST = 3;
	public static final int UP = 4;
	public static final int DOWN = 5;
	static final String[] FACE_NAMES = {"north", "east", "south", "west", "up", "down"};

	/** {@code parent}: the index of its parent in the list (before it), or -1 for a root. Rotation in degrees. */
	public record Bone(String name, int parent, float[] pivot, float[] rotation, List<Cube> cubes) {
	}

	/**
	 * {@code boxUv}: the box layout's corner (u, v), or null for per-face UVs ({@code faces}: per face {u, v, w, h, rotation°},
	 * null for a face that isn't drawn).
	 */
	public record Cube(float[] origin, float[] size, float inflate, float @Nullable [] pivot, float @Nullable [] rotation, boolean mirror,
		float @Nullable [] boxUv, float @Nullable [] @Nullable [] faces) {
	}

	/** Reads {@code bytes} (UTF-8 JSON), refusing anything past the limits with a reason. */
	public static CosmeticGeometry parse(byte[] bytes) throws Refused {
		if (bytes.length > CosmeticLimits.MAX_GEOMETRY_BYTES) {
			throw new Refused("geometry larger than " + CosmeticLimits.MAX_GEOMETRY_BYTES / 1024 + " KB");
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (!CosmeticLimits.shallow(text)) {
			throw new Refused("geometry nested too deep");
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (RuntimeException e) {
			throw new Refused("geometry isn't JSON");
		}
		if (!root.isJsonObject()) {
			throw new Refused("geometry isn't a JSON object");
		}
		JsonObject file = root.getAsJsonObject();
		String version = Json.string(file, "format_version");
		if (version == null || !atLeast(version, 1, 12)) {
			throw new Refused("geometry format " + (version == null ? "missing" : version) + " (1.12 or later is read)");
		}
		JsonElement list = file.get("minecraft:geometry");
		if (list == null || !list.isJsonArray() || list.getAsJsonArray().size() == 0 || !list.getAsJsonArray().get(0).isJsonObject()) {
			throw new Refused("no minecraft:geometry");
		}
		JsonObject geo = list.getAsJsonArray().get(0).getAsJsonObject();
		JsonObject description = Json.object(geo, "description");
		int tw = grid(description, "texture_width");
		int th = grid(description, "texture_height");
		JsonElement bonesJson = geo.get("bones");
		if (bonesJson != null && !bonesJson.isJsonArray()) {
			throw new Refused("bones isn't a list");
		}
		JsonArray bonesArray = bonesJson == null ? new JsonArray() : bonesJson.getAsJsonArray();
		if (bonesArray.size() > CosmeticLimits.MAX_BONES) {
			throw new Refused(bonesArray.size() + " bones (at most " + CosmeticLimits.MAX_BONES + ")");
		}
		List<String> names = new ArrayList<>();
		List<@Nullable String> parents = new ArrayList<>();
		List<float[]> pivots = new ArrayList<>();
		List<float[]> rotations = new ArrayList<>();
		List<List<Cube>> cubes = new ArrayList<>();
		int cubeCount = 0;
		for (JsonElement e : bonesArray) {
			if (!e.isJsonObject()) {
				throw new Refused("a bone isn't an object");
			}
			JsonObject b = e.getAsJsonObject();
			String name = Json.string(b, "name");
			if (name == null || name.isEmpty() || name.length() > CosmeticLimits.MAX_NAME) {
				throw new Refused("a bone without a usable name");
			}
			if (b.has("poly_mesh")) {
				throw new Refused("poly_mesh isn't supported (cubes only)");
			}
			String parent = Json.string(b, "parent");
			float[] pivot = vector(b, "pivot", 0, CosmeticLimits.MAX_COORDINATE);
			float[] rotation = vector(b, "rotation", 0, 1_000_000);
			boolean boneMirror = Json.bool(b, "mirror");
			float boneInflate = number(b, "inflate", 0, CosmeticLimits.MAX_INFLATE);
			List<Cube> own = new ArrayList<>();
			JsonElement cubesJson = b.get("cubes");
			if (cubesJson != null) {
				if (!cubesJson.isJsonArray()) {
					throw new Refused("cubes isn't a list");
				}
				for (JsonElement c : cubesJson.getAsJsonArray()) {
					if (++cubeCount > CosmeticLimits.MAX_CUBES) {
						throw new Refused("more than " + CosmeticLimits.MAX_CUBES + " cubes");
					}
					if (!c.isJsonObject()) {
						throw new Refused("a cube isn't an object");
					}
					own.add(cube(c.getAsJsonObject(), boneMirror, boneInflate));
				}
			}
			names.add(name);
			parents.add(parent);
			pivots.add(pivot);
			rotations.add(rotation);
			cubes.add(own);
		}
		return new CosmeticGeometry(tw, th, ordered(names, parents, pivots, rotations, cubes), cubeCount);
	}

	/** The bones with each parent before its children (a missing parent, or one in a loop, makes a root). */
	private static List<Bone> ordered(List<String> names, List<@Nullable String> parents, List<float[]> pivots, List<float[]> rotations,
		List<List<Cube>> cubes) {
		int n = names.size();
		Map<String, Integer> byName = new HashMap<>();
		for (int i = 0; i < n; i++) {
			byName.putIfAbsent(names.get(i), i);
		}
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) {
			String p = parents.get(i);
			Integer at = p == null ? null : byName.get(p);
			parent[i] = at == null || at == i ? -1 : at;
		}
		// Loops: a bone whose chain of parents comes back to it (or runs longer than there are bones) becomes a root.
		for (int i = 0; i < n; i++) {
			int steps = 0;
			for (int p = parent[i]; p >= 0; p = parent[p]) {
				if (p == i || ++steps > n) {
					parent[i] = -1;
					break;
				}
			}
		}
		int[] newIndex = new int[n];
		java.util.Arrays.fill(newIndex, -1);
		List<Bone> out = new ArrayList<>(n);
		boolean progress = true;
		while (out.size() < n && progress) {
			progress = false;
			for (int i = 0; i < n; i++) {
				if (newIndex[i] < 0 && (parent[i] < 0 || newIndex[parent[i]] >= 0)) {
					newIndex[i] = out.size();
					out.add(new Bone(names.get(i), parent[i] < 0 ? -1 : newIndex[parent[i]], pivots.get(i), rotations.get(i),
						List.copyOf(cubes.get(i))));
					progress = true;
				}
			}
		}
		return List.copyOf(out);
	}

	private static Cube cube(JsonObject c, boolean boneMirror, float boneInflate) throws Refused {
		float[] origin = vector(c, "origin", 0, CosmeticLimits.MAX_COORDINATE);
		float[] size = vector(c, "size", 0, CosmeticLimits.MAX_CUBE_SIDE);
		// Blockbench writes a cube dragged inside out with a negative size: the same box from the other corner, its two faces on
		// that axis swapped (their UVs stay with the side they are drawn on).
		boolean[] flipped = new boolean[3];
		for (int i = 0; i < 3; i++) {
			if (size[i] < 0) {
				origin[i] += size[i];
				size[i] = -size[i];
				flipped[i] = true;
			}
		}
		float inflate = c.has("inflate") ? number(c, "inflate", 0, CosmeticLimits.MAX_INFLATE) : boneInflate;
		float[] pivot = c.has("pivot") ? vector(c, "pivot", 0, CosmeticLimits.MAX_COORDINATE) : null;
		float[] rotation = c.has("rotation") ? vector(c, "rotation", 0, 1_000_000) : null;
		boolean mirror = c.has("mirror") ? Json.bool(c, "mirror") : boneMirror;
		JsonElement uv = c.get("uv");
		if (uv == null) {
			return new Cube(origin, size, inflate, pivot, rotation, mirror, new float[] {0, 0}, null);
		}
		if (uv.isJsonArray()) {
			return new Cube(origin, size, inflate, pivot, rotation, mirror, pair(uv.getAsJsonArray(), "uv"), null);
		}
		if (!uv.isJsonObject()) {
			throw new Refused("a cube's uv is neither a box nor faces");
		}
		float[][] faces = new float[6][];
		JsonObject f = uv.getAsJsonObject();
		for (int i = 0; i < 6; i++) {
			JsonObject face = Json.object(f, FACE_NAMES[i]);
			if (face == null) {
				continue;
			}
			JsonElement at = face.get("uv");
			JsonElement span = face.get("uv_size");
			if (at == null || !at.isJsonArray()) {
				continue;
			}
			float[] corner = pair(at.getAsJsonArray(), "uv");
			float[] extent = span != null && span.isJsonArray() ? pair(span.getAsJsonArray(), "uv_size") : new float[] {0, 0};
			float rot = number(face, "uv_rotation", 0, 1_000_000);
			faces[i] = new float[] {corner[0], corner[1], extent[0], extent[1], rot};
		}
		// x: east and west; y: up and down; z: north and south.
		swap(faces, flipped[0], EAST, WEST);
		swap(faces, flipped[1], UP, DOWN);
		swap(faces, flipped[2], NORTH, SOUTH);
		return new Cube(origin, size, inflate, pivot, rotation, mirror, null, faces);
	}

	private static void swap(float[][] faces, boolean flipped, int a, int b) {
		if (flipped) {
			float[] t = faces[a];
			faces[a] = faces[b];
			faces[b] = t;
		}
	}

	private static float[] pair(JsonArray a, String what) throws Refused {
		if (a.size() < 2) {
			throw new Refused(what + " needs two numbers");
		}
		return new float[] {finite(a.get(0), 8192), finite(a.get(1), 8192)};
	}

	/** A three-number list ({@code fallback} for each when absent), each within ±{@code max}. */
	private static float[] vector(JsonObject o, String key, float fallback, float max) throws Refused {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return new float[] {fallback, fallback, fallback};
		}
		if (!e.isJsonArray() || e.getAsJsonArray().size() < 3) {
			throw new Refused(key + " needs three numbers");
		}
		JsonArray a = e.getAsJsonArray();
		return new float[] {finite(a.get(0), max), finite(a.get(1), max), finite(a.get(2), max)};
	}

	private static float number(JsonObject o, String key, float fallback, float max) throws Refused {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? fallback : finite(e, max);
	}

	private static float finite(JsonElement e, float max) throws Refused {
		if (!(e instanceof JsonPrimitive p) || !p.isNumber() && !p.isString()) {
			throw new Refused("a number that isn't one");
		}
		double v;
		try {
			v = p.getAsDouble();
		} catch (NumberFormatException ex) {
			throw new Refused("a number that isn't one");
		}
		if (!Double.isFinite(v) || Math.abs(v) > max) {
			throw new Refused("a number out of range: " + v);
		}
		return (float) v;
	}

	private static int grid(@Nullable JsonObject description, String key) throws Refused {
		if (description == null || !description.has(key)) {
			return 64;
		}
		float v = finite(description.get(key), Integer.MAX_VALUE);
		if (v < 1 || v > CosmeticLimits.MAX_UV_GRID || v != Math.floor(v)) {
			throw new Refused(key + " " + v + " (1 to " + CosmeticLimits.MAX_UV_GRID + ")");
		}
		return (int) v;
	}

	/** Whether a version text ("1.12.0") is at least {@code major.minor}. */
	static boolean atLeast(String version, int major, int minor) {
		String[] parts = version.trim().split("\\.");
		try {
			int a = Integer.parseInt(parts[0]);
			int b = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
			return a > major || a == major && b >= minor;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
