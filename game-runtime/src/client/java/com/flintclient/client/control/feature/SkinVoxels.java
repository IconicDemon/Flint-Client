package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D Skins' geometry: each opaque pixel of a skin's outer layer (hat, jacket, sleeves, trousers) becomes a small box
 * standing on the body, instead of the flat layer floating over it. Boxes are in the model part's own units (16 to a
 * block, y pointing down, as Minecraft's player model is built), axis-aligned, each with the one texel it shows. Faces
 * between two neighbouring boxes and faces pointing into the body are left out. Pure Java; built once per skin.
 *
 * <p>The layer boxes are Minecraft's own (positions, sizes and texture offsets of the standard 64x64 player skin, the
 * same since 1.8), so this works for any version that uses that layout.
 */
public final class SkinVoxels {
	/** One box face, by the direction it faces; the bit is its place in {@link Voxel#faces}. */
	public enum Face {
		DOWN(0, -1, 0),
		UP(0, 1, 0),
		NORTH(0, 0, -1),
		SOUTH(0, 0, 1),
		WEST(-1, 0, 0),
		EAST(1, 0, 0);

		public final int dx;
		public final int dy;
		public final int dz;

		Face(int dx, int dy, int dz) {
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
		}

		public int bit() {
			return 1 << ordinal();
		}

		Face opposite() {
			return switch (this) {
				case DOWN -> UP;
				case UP -> DOWN;
				case NORTH -> SOUTH;
				case SOUTH -> NORTH;
				case WEST -> EAST;
				case EAST -> WEST;
			};
		}
	}

	/** A box ({@code min..max}), the texel it shows ({@code u, v}: its centre, in pixels) and the faces to draw. */
	public record Voxel(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float u, float v, int faces) {
		public boolean has(Face face) {
			return (faces & face.bit()) != 0;
		}
	}

	/** One outer-layer box of the player model: where it is in its part, its size, and where its texture starts. */
	public record Layer(String name, float x, float y, float z, int width, int height, int depth, int u, int v) {
	}

	public static final Layer HAT = new Layer("hat", -4, -8, -4, 8, 8, 8, 32, 0);
	public static final Layer JACKET = new Layer("jacket", -4, 0, -2, 8, 12, 4, 16, 32);
	public static final Layer RIGHT_SLEEVE = new Layer("right_sleeve", -3, -2, -2, 4, 12, 4, 40, 32);
	public static final Layer RIGHT_SLEEVE_SLIM = new Layer("right_sleeve", -2, -2, -2, 3, 12, 4, 40, 32);
	public static final Layer LEFT_SLEEVE = new Layer("left_sleeve", -1, -2, -2, 4, 12, 4, 48, 48);
	public static final Layer LEFT_SLEEVE_SLIM = new Layer("left_sleeve", -1, -2, -2, 3, 12, 4, 48, 48);
	public static final Layer RIGHT_PANTS = new Layer("right_pants", -2, 0, -2, 4, 12, 4, 0, 32);
	public static final Layer LEFT_PANTS = new Layer("left_pants", -2, 0, -2, 4, 12, 4, 0, 48);

	/** The skin's pixels: whether the texel at (x, y) shows (its alpha is high enough). */
	@FunctionalInterface
	public interface Opacity {
		boolean opaque(int x, int y);
	}

	/**
	 * Below this alpha (0-255) a pixel of the outer layer counts as see-through. Minecraft draws the outer layer translucent,
	 * so a faint pixel (a tinted band, glasses) still shows: only fully clear pixels are left out.
	 */
	public static final int ALPHA_THRESHOLD = 1;

	private SkinVoxels() {
	}

	/**
	 * The voxels of {@code layer}: each opaque texel a box {@code size} units across (1 is exactly one pixel) and
	 * {@code depth} units thick, standing on the base body from just under its surface.
	 */
	public static List<Voxel> build(Layer layer, Opacity pixels, float size, float depth) {
		List<Voxel> out = new ArrayList<>();
		int w = layer.width();
		int h = layer.height();
		int d = layer.depth();
		float x0 = layer.x();
		float y0 = layer.y();
		float z0 = layer.z();
		int u = layer.u();
		int v = layer.v();
		// Each face: its texel rectangle, then how a texel (i, j) maps onto the body surface.
		// As Minecraft's ModelPart.Cube maps its net: DOWN (y = min, the top of the model) and UP run along +x and -z; WEST
		// along -z and +y; NORTH along +x and +y; EAST along +z and +y; SOUTH along -x and +y.
		face(out, pixels, Face.DOWN, u + d, v, w, d, (i, j) -> new float[] {x0 + i + 0.5F, y0, z0 + d - j - 0.5F}, size, depth);
		face(out, pixels, Face.UP, u + d + w, v, w, d, (i, j) -> new float[] {x0 + i + 0.5F, y0 + h, z0 + d - j - 0.5F}, size, depth);
		face(out, pixels, Face.WEST, u, v + d, d, h, (i, j) -> new float[] {x0, y0 + j + 0.5F, z0 + d - i - 0.5F}, size, depth);
		face(out, pixels, Face.NORTH, u + d, v + d, w, h, (i, j) -> new float[] {x0 + i + 0.5F, y0 + j + 0.5F, z0}, size, depth);
		face(out, pixels, Face.EAST, u + d + w, v + d, d, h, (i, j) -> new float[] {x0 + w, y0 + j + 0.5F, z0 + i + 0.5F}, size, depth);
		face(out, pixels, Face.SOUTH, u + d + w + d, v + d, w, h, (i, j) -> new float[] {x0 + w - i - 0.5F, y0 + j + 0.5F, z0 + d}, size, depth);
		return out;
	}

	@FunctionalInterface
	private interface Place {
		float[] at(int i, int j);
	}

	private static void face(List<Voxel> out, Opacity pixels, Face face, int tu, int tv, int cols, int rows, Place place, float size, float depth) {
		for (int j = 0; j < rows; j++) {
			for (int i = 0; i < cols; i++) {
				if (!pixels.opaque(tu + i, tv + j)) {
					continue;
				}
				float[] c = place.at(i, j);
				// The box: centred on the texel in the face's plane, from just inside the body outwards.
				float half = size / 2;
				float inner = 0.05F;
				float minX = c[0] - half;
				float maxX = c[0] + half;
				float minY = c[1] - half;
				float maxY = c[1] + half;
				float minZ = c[2] - half;
				float maxZ = c[2] + half;
				switch (face) {
					case DOWN -> {
						minY = c[1] - depth;
						maxY = c[1] + inner;
					}
					case UP -> {
						minY = c[1] - inner;
						maxY = c[1] + depth;
					}
					case WEST -> {
						minX = c[0] - depth;
						maxX = c[0] + inner;
					}
					case EAST -> {
						minX = c[0] - inner;
						maxX = c[0] + depth;
					}
					case NORTH -> {
						minZ = c[2] - depth;
						maxZ = c[2] + inner;
					}
					case SOUTH -> {
						minZ = c[2] - inner;
						maxZ = c[2] + depth;
					}
				}
				int faces = 0;
				for (Face f : Face.values()) {
					if (f == face.opposite()) {
						continue;
					}
					if (f != face && neighbourOpaque(pixels, face, f, tu, tv, cols, rows, i, j)) {
						continue;
					}
					faces |= f.bit();
				}
				out.add(new Voxel(minX, minY, minZ, maxX, maxY, maxZ, tu + i + 0.5F, tv + j + 0.5F, faces));
			}
		}
	}

	/** Whether the texel next to (i, j) in the direction {@code side} (in the face's plane) is opaque. */
	private static boolean neighbourOpaque(Opacity pixels, Face face, Face side, int tu, int tv, int cols, int rows, int i, int j) {
		int[] step = step(face, side);
		if (step == null) {
			return false;
		}
		int ni = i + step[0];
		int nj = j + step[1];
		return ni >= 0 && nj >= 0 && ni < cols && nj < rows && pixels.opaque(tu + ni, tv + nj);
	}

	/** How (i, j) changes moving towards {@code side} on {@code face}, by the same placement as {@link #build}; null if never. */
	static int[] step(Face face, Face side) {
		return switch (face) {
			case DOWN, UP -> switch (side) {
				case EAST -> new int[] {1, 0};
				case WEST -> new int[] {-1, 0};
				case NORTH -> new int[] {0, 1};
				case SOUTH -> new int[] {0, -1};
				default -> null;
			};
			case WEST -> switch (side) {
				case NORTH -> new int[] {1, 0};
				case SOUTH -> new int[] {-1, 0};
				case UP -> new int[] {0, 1};
				case DOWN -> new int[] {0, -1};
				default -> null;
			};
			case NORTH -> switch (side) {
				case EAST -> new int[] {1, 0};
				case WEST -> new int[] {-1, 0};
				case UP -> new int[] {0, 1};
				case DOWN -> new int[] {0, -1};
				default -> null;
			};
			case EAST -> switch (side) {
				case SOUTH -> new int[] {1, 0};
				case NORTH -> new int[] {-1, 0};
				case UP -> new int[] {0, 1};
				case DOWN -> new int[] {0, -1};
				default -> null;
			};
			case SOUTH -> switch (side) {
				case WEST -> new int[] {1, 0};
				case EAST -> new int[] {-1, 0};
				case UP -> new int[] {0, 1};
				case DOWN -> new int[] {0, -1};
				default -> null;
			};
		};
	}
}
