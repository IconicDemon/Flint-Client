package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 3D Skins' geometry: a skin's outer layer (hat, jacket, sleeves, trousers) as a shell of pixel-sized boxes around the
 * body instead of a flat layer. Each layer box (8 by 8 by 8 for the hat) is a grid of cells; every opaque texel of one of
 * its six faces fills the cell it sits on, so the shell is one cell thick and its edges and corners are whole. The shell
 * is then scaled about the box's centre, so it stands out of the body by a fraction of a pixel and its inner half stays
 * inside it.
 *
 * <p>The mesh is kept small, since every player draws it every frame:
 * <ul>
 * <li>each layer face is one quad over the whole face, textured as Minecraft's own box is: Minecraft's entity shaders
 * drop see-through texels, so it shows exactly the opaque cells' outsides;</li>
 * <li>a cell side shows only where nothing covers it: not between two filled cells, not on a face where that face's own
 * texel is (its quad covers it), not inside the body;</li>
 * <li>side strips along a row of cells are merged into one quad, textured across the row's texels;</li>
 * <li>an edge cell filled by two faces has the sides between them split along the diagonal, half in each face's texel,
 * so the two never fight for the same spot.</li>
 * </ul>
 *
 * <p>Positions are in the model part's units (16 to a block, y pointing down), already divided by 16 for drawing; texture
 * coordinates are fractions of the skin. Pure Java, built once per skin and scale.
 */
public final class SkinVoxels {
	/** A layer box face, as Minecraft's model boxes name them (DOWN is the top of a head: model y points down). */
	public enum Face {
		DOWN(1, -1),
		UP(1, 1),
		NORTH(2, -1),
		SOUTH(2, 1),
		WEST(0, -1),
		EAST(0, 1);

		/** 0: x, 1: y, 2: z. */
		public final int axis;
		/** -1 or 1: the way it faces along its axis. */
		public final int sign;

		Face(int axis, int sign) {
			this.axis = axis;
			this.sign = sign;
		}

		static final Face[] ALL = values();

		/** The face looking along {@code axis} the {@code sign} way. */
		static Face of(int axis, int sign) {
			for (Face f : ALL) {
				if (f.axis == axis && f.sign == sign) {
					return f;
				}
			}
			throw new IllegalArgumentException();
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

	/** The skin's alpha (0-255) at a texel; 0 outside the texture. */
	@FunctionalInterface
	public interface Alpha {
		int alpha(int x, int y);
	}

	/**
	 * From this alpha a texel shows. Minecraft's entity shaders drop any texel below a tenth of full alpha, so a fainter one
	 * would only hide its neighbours' sides and show nothing itself.
	 */
	public static final int PRESENT = 26;
	/** A texel this opaque hides what is behind it; a fainter one lets the sides of its neighbours show through. */
	public static final int SOLID = 255;

	/** The least a shell stands out of the body (a fraction of its size), so it never lies on the body's own faces. */
	public static final float MIN_SCALE = 1.03F;

	/** A built mesh: quads of four corners (positions in blocks, texture fractions) and the face each looks to. */
	public static final class Mesh {
		public static final Mesh EMPTY = new Mesh(new float[0], new float[0], new byte[0]);

		private final float[] xyz;
		private final float[] uv;
		private final byte[] faces;

		Mesh(float[] xyz, float[] uv, byte[] faces) {
			this.xyz = xyz;
			this.uv = uv;
			this.faces = faces;
		}

		public int quads() {
			return faces.length;
		}

		/** Corner {@code k} (0-3) of quad {@code q}: x, y, z in blocks. */
		public float x(int q, int k) {
			return xyz[q * 12 + k * 3];
		}

		public float y(int q, int k) {
			return xyz[q * 12 + k * 3 + 1];
		}

		public float z(int q, int k) {
			return xyz[q * 12 + k * 3 + 2];
		}

		public float u(int q, int k) {
			return uv[q * 8 + k * 2];
		}

		public float v(int q, int k) {
			return uv[q * 8 + k * 2 + 1];
		}

		/** The face (a {@link Face} ordinal) quad {@code q} looks to, for its normal. */
		public int face(int q) {
			return faces[q];
		}

		/** The raw arrays, for the renderer's tight loop: 12 floats a quad, 8 floats a quad. */
		public float[] positions() {
			return xyz;
		}

		public float[] textures() {
			return uv;
		}
	}

	private SkinVoxels() {
	}

	/**
	 * The mesh of {@code layer} from the skin's {@code pixels} ({@code textureW} by {@code textureH}), its shell scaled by
	 * {@code sx, sy, sz} about the box's centre (each at least {@link #MIN_SCALE}).
	 */
	public static Mesh build(Layer layer, Alpha pixels, float sx, float sy, float sz, int textureW, int textureH) {
		return new Builder(layer, pixels, new float[] {Math.max(MIN_SCALE, sx), Math.max(MIN_SCALE, sy), Math.max(MIN_SCALE, sz)}, textureW,
			textureH).build();
	}

	/** The mesh of {@code layer} scaled evenly by {@code scale}, from a 64 by 64 skin. */
	public static Mesh build(Layer layer, Alpha pixels, float scale) {
		return build(layer, pixels, scale, scale, scale, 64, 64);
	}

	private static final class Builder {
		private final Layer layer;
		private final Alpha pixels;
		private final float[] scale;
		private final float textureW;
		private final float textureH;
		/** The grid's size along x, y, z. */
		private final int[] size;
		/** Per cell: the alpha each face shows on it (0: none), six to a cell. */
		private final int[] alpha;
		private final List<float[]> corners = new ArrayList<>();
		private final List<float[]> texture = new ArrayList<>();
		private final List<Byte> facing = new ArrayList<>();
		/** Side strips waiting to be merged, as {face looked to, owner face, plane cell, row cell, along cell}. */
		private final List<int[]> strips = new ArrayList<>();

		Builder(Layer layer, Alpha pixels, float[] scale, int textureW, int textureH) {
			this.layer = layer;
			this.pixels = pixels;
			this.scale = scale;
			this.textureW = textureW;
			this.textureH = textureH;
			this.size = new int[] {layer.width(), layer.height(), layer.depth()};
			this.alpha = new int[size[0] * size[1] * size[2] * 6];
		}

		private int index(int i, int j, int k) {
			return (i * size[1] + j) * size[2] + k;
		}

		/** The texel a face shows on cell (i, j, k), as {u, v} in pixels, or null when the cell isn't on that face. */
		private int[] texel(Face f, int i, int j, int k) {
			int w = size[0];
			int h = size[1];
			int d = size[2];
			int u = layer.u();
			int v = layer.v();
			return switch (f) {
				case DOWN -> j == 0 ? new int[] {u + d + i, v + d - 1 - k} : null;
				case UP -> j == h - 1 ? new int[] {u + d + w + i, v + d - 1 - k} : null;
				case WEST -> i == 0 ? new int[] {u + d - 1 - k, v + d + j} : null;
				case NORTH -> k == 0 ? new int[] {u + d + i, v + d + j} : null;
				case EAST -> i == w - 1 ? new int[] {u + d + w + k, v + d + j} : null;
				case SOUTH -> k == d - 1 ? new int[] {u + 2 * d + 2 * w - 1 - i, v + d + j} : null;
			};
		}

		/**
		 * The texture position (pixels) a face's net gives a point (x, y, z) of the unscaled grid: the inverse of
		 * {@link #texel}, continuous, so a quad's corners can be mapped one by one.
		 */
		private float[] net(Face f, float x, float y, float z) {
			int w = size[0];
			int d = size[2];
			int u = layer.u();
			int v = layer.v();
			return switch (f) {
				case DOWN -> new float[] {u + d + x, v + d - z};
				case UP -> new float[] {u + d + w + x, v + d - z};
				case WEST -> new float[] {u + d - z, v + d + y};
				case NORTH -> new float[] {u + d + x, v + d + y};
				case EAST -> new float[] {u + d + w + z, v + d + y};
				case SOUTH -> new float[] {u + 2 * d + 2 * w - x, v + d + y};
			};
		}

		private int alphaOf(Face f, int i, int j, int k) {
			return alpha[index(i, j, k) * 6 + f.ordinal()];
		}

		private boolean filled(int i, int j, int k) {
			int base = index(i, j, k) * 6;
			for (int f = 0; f < 6; f++) {
				if (alpha[base + f] >= PRESENT) {
					return true;
				}
			}
			return false;
		}

		private int strongest(int i, int j, int k) {
			int base = index(i, j, k) * 6;
			int best = 0;
			for (int f = 0; f < 6; f++) {
				best = Math.max(best, alpha[base + f]);
			}
			return best;
		}

		Mesh build() {
			boolean[] faceShown = new boolean[6];
			for (int i = 0; i < size[0]; i++) {
				for (int j = 0; j < size[1]; j++) {
					for (int k = 0; k < size[2]; k++) {
						for (Face f : Face.ALL) {
							int[] t = texel(f, i, j, k);
							if (t == null) {
								continue;
							}
							int a = t[0] >= 0 && t[1] >= 0 && t[0] < textureW && t[1] < textureH ? pixels.alpha(t[0], t[1]) & 0xFF : 0;
							if (a >= PRESENT) {
								alpha[index(i, j, k) * 6 + f.ordinal()] = a;
								faceShown[f.ordinal()] = true;
							}
						}
					}
				}
			}
			// One quad over each face that shows anything: the shader drops its clear texels.
			for (Face f : Face.ALL) {
				if (faceShown[f.ordinal()]) {
					float[] lo = {0, 0, 0};
					float[] hi = {size[0], size[1], size[2]};
					if (f.sign < 0) {
						hi[f.axis] = 0;
					} else {
						lo[f.axis] = size[f.axis];
					}
					quad(f, lo, hi, f, -1);
				}
			}
			for (int i = 0; i < size[0]; i++) {
				for (int j = 0; j < size[1]; j++) {
					for (int k = 0; k < size[2]; k++) {
						if (filled(i, j, k)) {
							sides(i, j, k);
						}
					}
				}
			}
			mergeStrips();
			int n = facing.size();
			float[] xyz = new float[n * 12];
			float[] uv = new float[n * 8];
			byte[] faces = new byte[n];
			for (int q = 0; q < n; q++) {
				System.arraycopy(corners.get(q), 0, xyz, q * 12, 12);
				System.arraycopy(texture.get(q), 0, uv, q * 8, 8);
				faces[q] = facing.get(q);
			}
			return n == 0 ? Mesh.EMPTY : new Mesh(xyz, uv, faces);
		}

		/** The exposed sides of a filled cell. */
		private void sides(int i, int j, int k) {
			int[] cell = {i, j, k};
			for (Face d : Face.ALL) {
				int along = cell[d.axis];
				boolean onPlane = d.sign < 0 ? along == 0 : along == size[d.axis] - 1;
				if (onPlane) {
					if (alphaOf(d, i, j, k) >= PRESENT) {
						// That face's own quad covers it.
						continue;
					}
				} else {
					int[] n = cell.clone();
					n[d.axis] += d.sign;
					if (filled(n[0], n[1], n[2])) {
						// Covered by the next cell, unless this one is solid and that one lets it show through.
						boolean solidHere = strongest(i, j, k) >= SOLID;
						boolean solidThere = strongest(n[0], n[1], n[2]) >= SOLID;
						if (solidThere || !solidHere) {
							continue;
						}
					}
				}
				// Whose texel it shows: the faces filling this cell that lie across it (its sides), else the one it backs.
				Face first = null;
				Face second = null;
				Face back = null;
				for (Face f : Face.ALL) {
					if (alphaOf(f, i, j, k) < PRESENT) {
						continue;
					}
					if (f.axis == d.axis) {
						if (f.sign != d.sign) {
							back = f;
						}
					} else if (first == null) {
						first = f;
					} else {
						second = f;
					}
				}
				if (first == null && back == null) {
					continue;
				}
				float[] lo = {i, j, k};
				float[] hi = {i + 1, j + 1, k + 1};
				if (d.sign < 0) {
					hi[d.axis] = lo[d.axis];
				} else {
					lo[d.axis] = hi[d.axis];
				}
				if (inside(lo, hi)) {
					continue;
				}
				if (first == null) {
					// The inner side of a texel's box, seen past the body's edge: that texel, as its face shows it.
					quad(d, lo, hi, back, -1);
				} else if (second == null) {
					// A strip candidate: merged with its neighbours along the row later.
					int alongAxis = 3 - d.axis - first.axis;
					strips.add(new int[] {d.ordinal(), first.ordinal(), cell[d.axis], cell[first.axis], cell[alongAxis]});
				} else {
					split(d, first, second, i, j, k, lo, hi);
				}
			}
		}

		/** Whether a quad (unscaled grid box, flat on one axis) ends up within the body once scaled: hidden there. */
		private boolean inside(float[] lo, float[] hi) {
			for (int a = 0; a < 3; a++) {
				float l = scaled(a, lo[a]);
				float h = scaled(a, hi[a]);
				float eps = 1e-4F;
				if (l < -eps || h > size[a] + eps) {
					return false;
				}
			}
			return true;
		}

		/** A grid coordinate along {@code axis} once scaled about the box's centre (still in grid units). */
		private float scaled(int axis, float value) {
			float c = size[axis] / 2.0F;
			return c + (value - c) * scale[axis];
		}

		/** Merges side strips in a row (same side, same owner, same plane and row, cells one after another). */
		private void mergeStrips() {
			strips.sort((a, b) -> {
				for (int x = 0; x < 5; x++) {
					int c = Integer.compare(a[x], b[x]);
					if (c != 0) {
						return c;
					}
				}
				return 0;
			});
			int s = 0;
			while (s < strips.size()) {
				int[] start = strips.get(s);
				int e = s;
				while (e + 1 < strips.size()) {
					int[] next = strips.get(e + 1);
					if (next[0] == start[0] && next[1] == start[1] && next[2] == start[2] && next[3] == start[3] && next[4] == strips.get(e)[4] + 1) {
						e++;
					} else {
						break;
					}
				}
				Face d = Face.ALL[start[0]];
				Face owner = Face.ALL[start[1]];
				int alongAxis = 3 - d.axis - owner.axis;
				float[] lo = new float[3];
				float[] hi = new float[3];
				lo[d.axis] = start[2] + (d.sign > 0 ? 1 : 0);
				hi[d.axis] = lo[d.axis];
				lo[owner.axis] = start[3];
				hi[owner.axis] = start[3] + 1;
				lo[alongAxis] = start[4];
				hi[alongAxis] = strips.get(e)[4] + 1;
				quad(d, lo, hi, owner, start[2]);
				s = e + 1;
			}
		}

		/**
		 * A quad looking to {@code d} over the grid box {@code lo..hi} (flat on d's axis), textured from {@code owner}'s net.
		 * For a side ({@code centreCell} >= 0) the texture is read at the middle of that cell along d's axis, so it never
		 * falls on the line between two texels.
		 */
		private void quad(Face d, float[] lo, float[] hi, Face owner, int centreCell) {
			float[][] p = cornersOf(d, lo, hi);
			emit(d, p, owner, centreCell);
		}

		/** The corners of a flat box looking to {@code d}, in Minecraft's own order for that face (outward winding). */
		private static float[][] cornersOf(Face d, float[] lo, float[] hi) {
			float x0 = lo[0];
			float y0 = lo[1];
			float z0 = lo[2];
			float x1 = hi[0];
			float y1 = hi[1];
			float z1 = hi[2];
			float[] t0 = {x0, y0, z0};
			float[] t1 = {x1, y0, z0};
			float[] t2 = {x1, y1, z0};
			float[] t3 = {x0, y1, z0};
			float[] l0 = {x0, y0, z1};
			float[] l1 = {x1, y0, z1};
			float[] l2 = {x1, y1, z1};
			float[] l3 = {x0, y1, z1};
			return switch (d) {
				case DOWN -> new float[][] {l1, l0, t0, t1};
				case UP -> new float[][] {t2, t3, l3, l2};
				case WEST -> new float[][] {t0, l0, l3, t3};
				case NORTH -> new float[][] {t1, t0, t3, t2};
				case EAST -> new float[][] {l1, t1, t2, l2};
				case SOUTH -> new float[][] {l0, l1, l2, l3};
			};
		}

		/** Emits a polygon of four corners (a triangle repeats its last), textured from {@code owner}'s net, scaled. */
		private void emit(Face d, float[][] p, Face owner, int centreCell) {
			float[] xyz = new float[12];
			float[] uv = new float[8];
			for (int k = 0; k < 4; k++) {
				float[] at = p[k];
				float tx = at[0];
				float ty = at[1];
				float tz = at[2];
				if (centreCell >= 0) {
					float mid = centreCell + 0.5F;
					if (d.axis == 0) {
						tx = mid;
					} else if (d.axis == 1) {
						ty = mid;
					} else {
						tz = mid;
					}
				}
				float[] t = net(owner, tx, ty, tz);
				uv[k * 2] = t[0] / textureW;
				uv[k * 2 + 1] = t[1] / textureH;
				xyz[k * 3] = (layer.x() + scaled(0, at[0])) / 16.0F;
				xyz[k * 3 + 1] = (layer.y() + scaled(1, at[1])) / 16.0F;
				xyz[k * 3 + 2] = (layer.z() + scaled(2, at[2])) / 16.0F;
			}
			corners.add(xyz);
			texture.add(uv);
			facing.add((byte) d.ordinal());
		}

		/**
		 * The side of an edge cell looking to {@code d}, shared by two faces' texels: cut along its diagonal from the
		 * cell's outer corner, each half nearer its own face in that face's texel.
		 */
		private void split(Face d, Face a, Face b, int i, int j, int k, float[] lo, float[] hi) {
			// The quad spans a's axis and b's axis; each face's outer edge is where its plane is.
			float aOut = a.sign < 0 ? lo[a.axis] : hi[a.axis];
			float aIn = a.sign < 0 ? hi[a.axis] : lo[a.axis];
			float bOut = b.sign < 0 ? lo[b.axis] : hi[b.axis];
			float bIn = b.sign < 0 ? hi[b.axis] : lo[b.axis];
			float plane = lo[d.axis];
			float[] outer = point(d.axis, plane, a.axis, aOut, b.axis, bOut);
			float[] inner = point(d.axis, plane, a.axis, aIn, b.axis, bIn);
			float[] nearA = point(d.axis, plane, a.axis, aOut, b.axis, bIn);
			float[] nearB = point(d.axis, plane, a.axis, aIn, b.axis, bOut);
			int cell = d.axis == 0 ? i : d.axis == 1 ? j : k;
			triangle(d, outer, nearA, inner, a, cell);
			triangle(d, outer, nearB, inner, b, cell);
		}

		private static float[] point(int axis1, float v1, int axis2, float v2, int axis3, float v3) {
			float[] p = new float[3];
			p[axis1] = v1;
			p[axis2] = v2;
			p[axis3] = v3;
			return p;
		}

		/** A triangle as a quad with its last corner repeated, wound to look to {@code d}. */
		private void triangle(Face d, float[] p0, float[] p1, float[] p2, Face owner, int cell) {
			float[] e1 = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]};
			float[] e2 = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
			float[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
			boolean outward = n[d.axis] * d.sign > 0;
			float[][] corners = outward ? new float[][] {p0, p1, p2, p2} : new float[][] {p0, p2, p1, p1};
			emit(d, corners, owner, cell);
		}
	}

	/** How many quads a list of meshes has, for the dev tour and tests. */
	public static int quads(List<Mesh> meshes) {
		int n = 0;
		for (Mesh m : meshes) {
			n += m.quads();
		}
		return n;
	}

	/** A copy of a face's corner positions for tests: quad q as 12 floats. */
	static float[] quad(Mesh mesh, int q) {
		return Arrays.copyOfRange(mesh.positions(), q * 12, q * 12 + 12);
	}
}
