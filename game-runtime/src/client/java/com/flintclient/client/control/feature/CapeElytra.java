package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.List;

/**
 * The cape-derived elytra: a Store cape drawn on Minecraft's own elytra, static or animated, as Flint's websites preview it.
 * Mapping {@code cape-derived-elytra}, version 2 ("vanilla elytra"): the authoritative spec is the handoff
 * {@code cape-elytra-vanilla-v2.md}, and this class is its reference implementation. Change both together and bump
 * {@link #VERSION}.
 *
 * <ul>
 * <li><b>Geometry: Minecraft's elytra, unchanged.</b> Each wing is the elytra model's own box ({@code ElytraModel.createLayer},
 * the same from 1.21.1 to 26.3): {@code texOffs(22, 0)}, a 10×20×2 box inflated by 1 ({@code CubeDeformation(1.0)}), the right
 * wing {@code mirror()}ed, its faces built exactly as {@code ModelPart.Cube} builds them (vertex order, texture corners, the
 * mirror's reversal and normals). Minecraft's wing parts still place them (pivots, its smoothed wing angles, crouching, gliding),
 * and the layer's offset behind the body.</li>
 * <li><b>Silhouette: Minecraft's.</b> Minecraft's elytra is that box with a cut-out texture: what shows is only the texels its
 * {@code elytra.png} draws. {@link #MASK} is that texture's alpha (its 24×22 wing region), and only those texels are drawn: each
 * face is cut along texel boundaries into one quad per run of drawn texels in a row. Identical to Minecraft's alpha test.</li>
 * <li><b>Texture: the cape's own.</b> Each face of the elytra box samples, linearly, the face of Minecraft's cape box
 * ({@code texOffs(0, 0)}, 10×16×1, worn turned half round) that faces the same way on the player ({@link #capeRect}): the outside
 * the cape's front (its 16 rows stretched over the wing's 20), the box sides its sides, the top and bottom strips its top and
 * bottom; the inside would take its lining, but Minecraft's elytra draws nothing there. The right wing is the left one's mirror
 * image, as Minecraft mirrors it.</li>
 * <li>Drawn like Minecraft's elytra: cut out, both sides (no back-face culling), so from the inside the outside shows through as
 * on any elytra; the glint when enchanted.</li>
 * <li>Texture coordinates are units of the cape's 64×32 net, divided by 64 and 32 for the texture the cape itself is drawn with
 * (a static cape, a poster, or an animated cape's texture its frames are written into), so the wings always show the cape's
 * current frame: one texture and one clock, at any resolution.</li>
 * </ul>
 * Pure Java; version-neutral.
 */
public final class CapeElytra {
	public static final String MAPPING = "cape-derived-elytra";
	public static final int VERSION = 2;
	/** Interleaved corners: x, y, z (blocks, the wing part's space), u, v (normalised on the cape texture), nx, ny, nz. */
	public static final int FLOATS_PER_VERTEX = 8;

	/** The elytra texture size and the wing box (texture offset, size, inflation), as {@code ElytraModel.createLayer} has them. */
	static final int TEX_W = 64;
	static final int TEX_H = 32;
	static final int ELYTRA_U = 22;
	static final int ELYTRA_V = 0;
	static final float[] LEFT_BOX = {-10, 0, 0};
	static final float[] RIGHT_BOX = {0, 0, 0};
	static final int BOX_W = 10;
	static final int BOX_H = 20;
	static final int BOX_D = 2;
	static final float GROW = 1;
	/** Minecraft's cape box ({@code texOffs(0, 0)}, 10×16×1): the regions the wings read. */
	static final int CAPE_W = 10;
	static final int CAPE_H = 16;
	static final int CAPE_D = 1;

	/**
	 * Minecraft's elytra texture's alpha in its wing region, texels x 22..45 (columns) by y 0..21 (rows): '#' drawn, '.' clear.
	 * Read from {@code textures/entity/equipment/wings/elytra.png} (26.x) and {@code textures/entity/elytra.png} (1.21.1); the
	 * two are the same ({@code CapeElytraTest} checks it against the game's own texture). Only the shape is kept.
	 */
	static final int MASK_X = 22;
	static final int MASK_Y = 0;
	static final String[] MASK = {
		".........#########......",
		"..........##............",
		"............########....",
		"............#########...",
		".............#########..",
		".............#########..",
		".............#########..",
		".............##########.",
		".............##########.",
		".............##########.",
		".............##########.",
		"#.............##########",
		"#.............##########",
		"#.............##########",
		"#.............##########",
		"#.............##########",
		"#..............#########",
		"#..............#########",
		"#..............#########",
		"#...............########",
		"#...............########",
		"#................#######",
	};

	/** The faces in {@code ModelPart.Cube}'s order. */
	public enum Face {
		DOWN(0, -1, 0), UP(0, 1, 0), WEST(-1, 0, 0), NORTH(0, 0, -1), EAST(1, 0, 0), SOUTH(0, 0, 1);

		final int nx;
		final int ny;
		final int nz;

		Face(int nx, int ny, int nz) {
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
		}
	}

	private static final float[] LEFT_QUADS = build(false);
	private static final float[] RIGHT_QUADS = build(true);

	private CapeElytra() {
	}

	/**
	 * Whether a player's elytra is drawn with their cape (the contract's §8.2–8.3): the viewer's "Cape design on elytra" setting is
	 * on, the player wears a Flint Store cape that is loaded and verified, they show their cape (Minecraft's own rule for capes
	 * on elytras), Minecraft gives them no elytra texture of their own, and the elytra is the vanilla elytra (an item drawn with
	 * another equipment asset keeps its own look). Otherwise Minecraft's elytra, exactly as without Flint.
	 */
	public static boolean use(boolean setting, boolean storeCape, boolean showCape, boolean minecraftElytraTexture, boolean vanillaElytra) {
		return setting && storeCape && showCape && !minecraftElytraTexture && vanillaElytra;
	}

	/**
	 * One wing as Minecraft draws it: quads, {@link #FLOATS_PER_VERTEX} floats a corner, in the wing part's space, corners in
	 * {@code ModelPart.Cube}'s order. Shared; never modified.
	 */
	public static float[] wingQuads(boolean right) {
		return right ? RIGHT_QUADS : LEFT_QUADS;
	}

	/** Whether Minecraft's elytra texture draws texel (x, y). */
	static boolean drawn(int x, int y) {
		int row = y - MASK_Y;
		int col = x - MASK_X;
		return row >= 0 && row < MASK.length && col >= 0 && col < MASK[row].length() && MASK[row].charAt(col) == '#';
	}

	/**
	 * A face's texture rectangle {u0, v0, u1, v1} as {@code ModelPart.Cube} hands it to {@code Polygon}, for a box at texture
	 * offset (u, v) of size w × h × d. Corner 0 of the face takes (u1, v0), corner 1 (u0, v0), corner 2 (u0, v1), corner 3 (u1, v1).
	 */
	static int[] rect(Face face, int u, int v, int w, int h, int d) {
		return switch (face) {
			case DOWN -> new int[] {u + d, v, u + d + w, v + d};
			case UP -> new int[] {u + d + w, v + d, u + d + w + w, v};
			case WEST -> new int[] {u, v + d, u + d, v + d + h};
			case NORTH -> new int[] {u + d, v + d, u + d + w, v + d + h};
			case EAST -> new int[] {u + d + w, v + d, u + d + w + d, v + d + h};
			case SOUTH -> new int[] {u + d + w + d, v + d, u + d + w + d + w, v + d + h};
		};
	}

	/**
	 * The cape region a face of the wing reads: the face of Minecraft's cape box that faces the same way on the player. Minecraft
	 * wears the cape box turned half round (yaw 180°), so the wing's outside (south) reads the cape's front (its north region,
	 * 1,1), its inside the lining (south, 12,1), its −x side the cape's east strip and its +x side the west strip; the top and
	 * bottom strips read the cape's own, turned half round ({@link #turned}). Orientation follows the cape as worn: the wing's
	 * outside shows the front as the cape shows it, seen from behind.
	 */
	static int[] capeRect(Face face) {
		Face worn = switch (face) {
			case SOUTH -> Face.NORTH;
			case NORTH -> Face.SOUTH;
			case WEST -> Face.EAST;
			case EAST -> Face.WEST;
			default -> face;
		};
		return rect(worn, 0, 0, CAPE_W, CAPE_H, CAPE_D);
	}

	/** Whether a face reads its cape region with both axes reversed (the top and bottom strips: the cape is worn turned). */
	static boolean turned(Face face) {
		return face == Face.DOWN || face == Face.UP;
	}

	/**
	 * A face's four corners (x, y, z in model pixels) in {@code ModelPart.Cube}'s order before the mirror's reversal, for the box
	 * from {@code from}, inflated by {@link #GROW}, its x swapped when mirrored, exactly as the game builds it.
	 */
	static float[][] corners(Face face, float[] from, boolean mirror) {
		float x0 = from[0] - GROW;
		float y0 = from[1] - GROW;
		float z0 = from[2] - GROW;
		float x1 = from[0] + BOX_W + GROW;
		float y1 = from[1] + BOX_H + GROW;
		float z1 = from[2] + BOX_D + GROW;
		if (mirror) {
			float t = x1;
			x1 = x0;
			x0 = t;
		}
		float[] v1 = {x0, y0, z0};
		float[] v2 = {x1, y0, z0};
		float[] v3 = {x1, y1, z0};
		float[] v4 = {x0, y1, z0};
		float[] v5 = {x0, y0, z1};
		float[] v6 = {x1, y0, z1};
		float[] v7 = {x1, y1, z1};
		float[] v8 = {x0, y1, z1};
		return switch (face) {
			case DOWN -> new float[][] {v6, v5, v1, v2};
			case UP -> new float[][] {v3, v4, v8, v7};
			case WEST -> new float[][] {v1, v5, v8, v4};
			case NORTH -> new float[][] {v2, v1, v4, v3};
			case EAST -> new float[][] {v6, v2, v3, v7};
			case SOUTH -> new float[][] {v5, v6, v7, v8};
		};
	}

	private static float[] build(boolean mirror) {
		List<float[]> out = new ArrayList<>();
		float[] from = mirror ? RIGHT_BOX : LEFT_BOX;
		for (Face face : Face.values()) {
			int[] r = rect(face, ELYTRA_U, ELYTRA_V, BOX_W, BOX_H, BOX_D);
			int[] c = capeRect(face);
			float[][] k = corners(face, from, mirror);
			// The face is a parallelogram: corner 1 is (u0, v0), corner 0 is (u1, v0), corner 2 is (u0, v1).
			float[] origin = k[1];
			float[] du = sub(k[0], k[1]);
			float[] dv = sub(k[2], k[1]);
			// Minecraft mirrors an x-facing face's normal on a mirrored box.
			Face facing = mirror && (face == Face.WEST || face == Face.EAST) ? (face == Face.WEST ? Face.EAST : Face.WEST) : face;
			int xa = Math.min(r[0], r[2]);
			int xb = Math.max(r[0], r[2]);
			int ya = Math.min(r[1], r[3]);
			int yb = Math.max(r[1], r[3]);
			for (int y = ya; y < yb; y++) {
				int x = xa;
				while (x < xb) {
					if (!drawn(x, y)) {
						x++;
						continue;
					}
					int run = x;
					while (x < xb && drawn(x, y)) {
						x++;
					}
					quad(out, r, c, turned(face), origin, du, dv, facing, mirror, run, x, y, y + 1);
				}
			}
		}
		float[] all = new float[out.size() * FLOATS_PER_VERTEX];
		for (int i = 0; i < out.size(); i++) {
			System.arraycopy(out.get(i), 0, all, i * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
		}
		return all;
	}

	/** One run of drawn texels [xa, xb) × [ya, yb) of a face, as a quad in the face's own corner order. */
	private static void quad(List<float[]> out, int[] r, int[] c, boolean turned, float[] origin, float[] du, float[] dv, Face facing,
		boolean mirror, int xa, int xb, int ya, int yb) {
		double s0 = frac(xa, r[0], r[2]);
		double s1 = frac(xb, r[0], r[2]);
		double t0 = frac(ya, r[1], r[3]);
		double t1 = frac(yb, r[1], r[3]);
		double sLow = Math.min(s0, s1);
		double sHigh = Math.max(s0, s1);
		double tLow = Math.min(t0, t1);
		double tHigh = Math.max(t0, t1);
		double[][] st = {{sHigh, tLow}, {sLow, tLow}, {sLow, tHigh}, {sHigh, tHigh}};
		float[][] corners = new float[4][];
		for (int i = 0; i < 4; i++) {
			double s = st[i][0];
			double t = st[i][1];
			float[] v = new float[FLOATS_PER_VERTEX];
			v[0] = (float) ((origin[0] + s * du[0] + t * dv[0]) / 16.0);
			v[1] = (float) ((origin[1] + s * du[1] + t * dv[1]) / 16.0);
			v[2] = (float) ((origin[2] + s * du[2] + t * dv[2]) / 16.0);
			double cs = turned ? 1 - s : s;
			double ct = turned ? 1 - t : t;
			v[3] = (float) ((c[0] + cs * (c[2] - c[0])) / TEX_W);
			v[4] = (float) ((c[1] + ct * (c[3] - c[1])) / TEX_H);
			v[5] = facing.nx;
			v[6] = facing.ny;
			v[7] = facing.nz;
			corners[i] = v;
		}
		if (mirror) {
			for (int i = 3; i >= 0; i--) {
				out.add(corners[i]);
			}
		} else {
			for (int i = 0; i < 4; i++) {
				out.add(corners[i]);
			}
		}
	}

	/** Where texture coordinate x lies from a to b, 0..1. */
	private static double frac(int x, int a, int b) {
		return (double) (x - a) / (b - a);
	}

	private static float[] sub(float[] a, float[] b) {
		return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}
}
