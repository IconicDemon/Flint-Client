package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cape-derived elytra: a Store cape drawn as two matching elytra wings, static or animated, exactly as Flint's websites
 * preview it. Mapping {@code cape-derived-elytra}, version 1: the authoritative spec is the handoff
 * {@code cape-elytra-mapping.md}, and this class is a line-for-line port of its reference implementation,
 * {@code src/lib/cape-elytra.ts} (byte-identical in the Store, the Command Center and Creator): the same outline, mesh, vertex
 * order and texture coordinates, so the geometry it builds hashes to the websites' pinned {@code GEOMETRY_SHA256}
 * ({@code CapeElytraTest}). Change both together and bump {@link #VERSION}.
 *
 * <ul>
 * <li>Model space is Minecraft's (ModelPart pixels): y down, +x the player's left, +z behind. Each wing is built in its
 * elytra wing part's own space (pivot at the origin), so Minecraft's own wing pose (its smoothed angles, the crouch offset,
 * the elytra layer's offset) places it, exactly as it places the vanilla wing.</li>
 * <li>Each wing is the cape's 10×16 image, one texel = {@link #TEXEL} px, cut to a stepped elytra outline ({@link #STEPS}) and
 * {@link #THICKNESS} deep. Outside: the cape's front image, unmirrored as seen from outside; inside: its back image (the
 * lining) mirrored through the cloth; edges: the lining's outermost pixel line. The right wing is the left one mirrored in x.</li>
 * <li>Texture coordinates are atlas units of the cape region (the 22×17 atlas); {@link #wingQuads} divides them by 64 and 32,
 * for the 64×32-per-unit texture the cape itself is drawn with (a static cape, a poster, or an animated cape's texture its
 * frames are written into), so the wings always show the cape's current frame: one texture and one clock.</li>
 * </ul>
 * Pure Java; version-neutral.
 */
public final class CapeElytra {
	public static final String MAPPING = "cape-derived-elytra";
	public static final int VERSION = 1;

	/** Model pixels per texel of the cape image on a wing, and the wing's depth (one texel). */
	public static final double TEXEL = 1.25;
	public static final double THICKNESS = 1.25;
	/** The left wing in its own space: x from OUTER_X (design column 0) to OUTER_X − 10·TEXEL. */
	public static final double OUTER_X = 1.25;
	/** Its inside surface's z; the outside is at INNER_Z + THICKNESS. */
	public static final double INNER_Z = 0.375;
	/** Texture coordinates are pulled this far (atlas units) inside the image (and where edges sample the lining's line). */
	public static final double UV_INSET = 0.01;
	/** Interleaved vertices: x, y, z (model px), u, v (atlas units), nx, ny, nz. */
	public static final int FLOATS_PER_VERTEX = 8;

	/** The cape atlas regions the wings read [x, y, w, h], in atlas units (vanilla's cape box at texOffs(0, 0), 10×16×1). */
	static final int[] FRONT = {1, 1, 10, 16};
	static final int[] BACK = {12, 1, 10, 16};

	/** One flat piece of the wing: columns [c0, c1) of rows [r0, r1) of the cape image. */
	public record Step(int r0, int r1, int c0, int c1) {
	}

	/** The stepped outline: the outer edge steps in towards the tip at the bottom; the inner edge (column 10) is straight. */
	public static final List<Step> STEPS = List.of(
		new Step(0, 7, 0, 10),
		new Step(7, 9, 1, 10),
		new Step(9, 10, 2, 10),
		new Step(10, 11, 3, 10),
		new Step(11, 12, 4, 10),
		new Step(12, 13, 5, 10),
		new Step(13, 14, 6, 10),
		new Step(14, 15, 7, 10),
		new Step(15, 16, 8, 10));

	/** Which way an edge faces. */
	public enum Side {
		OUTER, INNER, TOP, BOTTOM
	}

	/** One edge of the outline, from grid point a to b (column, row), and the lining line it samples [u0, v0, u1, v1]. */
	public record Edge(Side side, int ca, int ra, int cb, int rb, double[] uv) {
	}

	/** The mesh both wings share: vertices and triangles, left wing first, as {@code buildElytraMesh} makes them. */
	public record Mesh(float[] vertices, int[] indices, int leftCount, int outsideCount, int insideCount, int edgeCount) {
		public int vertexCount() {
			return vertices.length / FLOATS_PER_VERTEX;
		}
	}

	private static final Mesh MESH = build();
	private static final float[] LEFT_QUADS = quads(MESH, false);
	private static final float[] RIGHT_QUADS = quads(MESH, true);

	private CapeElytra() {
	}

	/**
	 * Whether a player's elytra is drawn as their cape (the contract's §8.2–8.3): the viewer's "Cape design on elytra" setting is
	 * on, the player wears a Flint Store cape that is loaded and verified, they show their cape (Minecraft's own rule for capes
	 * on elytras), Minecraft gives them no elytra texture of their own, and the elytra is the vanilla elytra (an item drawn with
	 * another equipment asset keeps its own look). Otherwise Minecraft's elytra, exactly as without Flint.
	 */
	public static boolean use(boolean setting, boolean storeCape, boolean showCape, boolean minecraftElytraTexture, boolean vanillaElytra) {
		return setting && storeCape && showCape && !minecraftElytraTexture && vanillaElytra;
	}

	public static Mesh mesh() {
		return MESH;
	}

	/**
	 * One wing as Minecraft draws it: quads (each triangle as a quad with its last corner twice), 8 floats a corner: x, y, z in
	 * blocks (pixels / 16) in the wing part's space, u, v normalised for a 64×32-per-unit cape texture, nx, ny, nz. Wound
	 * counter-clockwise as seen from outside (the reference's triangles reversed: it is wound for {@code frontFace(CW)}), so
	 * Minecraft's back-face culling keeps the outside. Shared; never modified.
	 */
	public static float[] wingQuads(boolean right) {
		return right ? RIGHT_QUADS : LEFT_QUADS;
	}

	// ---------- texture coordinates ----------

	private static double insetU(int c) {
		return UV_INSET + (c * (10 - 2 * UV_INSET)) / 10;
	}

	private static double insetV(int r) {
		return 0 + UV_INSET + (r * (16 - 2 * UV_INSET)) / 16;
	}

	/** Front image: the outside at grid point (c, r). */
	static double[] frontUv(int c, int r) {
		return new double[] {FRONT[0] + insetU(c), FRONT[1] + insetV(r)};
	}

	/** Back image, mirrored through the cloth: the inside at grid point (c, r). */
	static double[] backUv(int c, int r) {
		return new double[] {BACK[0] + insetU(10 - c), BACK[1] + insetV(r)};
	}

	/** The lining's pixel line just inside an edge (design column boundary c is back column boundary 10 − c). */
	private static double lineU(int c, boolean insideIsRight) {
		return BACK[0] + (10 - c) + (insideIsRight ? -UV_INSET : UV_INSET);
	}

	private static double lineV(int r, boolean insideIsBelow) {
		return BACK[1] + 0 + (r * 16) / 16.0 + (insideIsBelow ? UV_INSET : -UV_INSET);
	}

	private static double edgeU(int c) {
		return BACK[0] + insetU(10 - c);
	}

	private static double edgeV(int r) {
		return BACK[1] + insetV(r);
	}

	/** The edges of the outline, in order round it, each split where the faces have a vertex (no T-junctions). */
	public static List<Edge> edges() {
		List<Edge> out = new ArrayList<>();
		Step top = STEPS.get(0);
		Step last = STEPS.get(STEPS.size() - 1);
		out.add(new Edge(Side.TOP, top.c0(), 0, top.c1(), 0, new double[] {edgeU(top.c0()), lineV(0, true), edgeU(top.c1()), lineV(0, true)}));
		for (Step s : STEPS) {
			out.add(new Edge(Side.INNER, s.c1(), s.r0(), s.c1(), s.r1(),
				new double[] {lineU(s.c1(), false), edgeV(s.r0()), lineU(s.c1(), false), edgeV(s.r1())}));
		}
		out.add(new Edge(Side.BOTTOM, last.c1(), 16, last.c0(), 16,
			new double[] {edgeU(last.c1()), lineV(16, false), edgeU(last.c0()), lineV(16, false)}));
		for (int i = STEPS.size() - 1; i >= 0; i--) {
			Step s = STEPS.get(i);
			Step above = i > 0 ? STEPS.get(i - 1) : null;
			out.add(new Edge(Side.OUTER, s.c0(), s.r1(), s.c0(), s.r0(),
				new double[] {lineU(s.c0(), true), edgeV(s.r1()), lineU(s.c0(), true), edgeV(s.r0())}));
			if (above != null && above.c0() < s.c0()) {
				out.add(new Edge(Side.BOTTOM, s.c0(), s.r0(), above.c0(), s.r0(),
					new double[] {edgeU(s.c0()), lineV(s.r0(), false), edgeU(above.c0()), lineV(s.r0(), false)}));
			}
		}
		return out;
	}

	// ---------- geometry ----------

	/** The left wing at grid point (c, r), on the inside (z = INNER_Z) or the outside (z = INNER_Z + THICKNESS). */
	private static double[] at(int c, int r, boolean outside) {
		return new double[] {OUTER_X - c * TEXEL, r * TEXEL, outside ? INNER_Z + THICKNESS : INNER_Z};
	}

	/** The reference's builder, step for step (the vertex order is part of the golden hash). */
	private static final class Builder {
		final List<double[]> vertices = new ArrayList<>();
		final List<Integer> indices = new ArrayList<>();

		int vertex(double[] p, double[] uv, double[] n) {
			vertices.add(new double[] {p[0], p[1], p[2], uv[0], uv[1], n[0], n[1], n[2]});
			return vertices.size() - 1;
		}

		void tri(int a, int b, int c, double[] n) {
			double[] pa = vertices.get(a);
			double[] pb = vertices.get(b);
			double[] pc = vertices.get(c);
			double e1x = pb[0] - pa[0], e1y = pb[1] - pa[1], e1z = pb[2] - pa[2];
			double e2x = pc[0] - pa[0], e2y = pc[1] - pa[1], e2z = pc[2] - pa[2];
			double cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
			if (cx * n[0] + cy * n[1] + cz * n[2] > 0) {
				indices.add(a);
				indices.add(c);
				indices.add(b);
			} else {
				indices.add(a);
				indices.add(b);
				indices.add(c);
			}
		}

		/** A face: one fan per step (a rectangle, plus a vertex where the step below starts), sharing grid vertices. */
		void face(boolean outside) {
			double[] n = {0, 0, outside ? 1 : -1};
			Map<Long, Integer> grid = new LinkedHashMap<>();
			for (int i = 0; i < STEPS.size(); i++) {
				Step s = STEPS.get(i);
				Step next = i + 1 < STEPS.size() ? STEPS.get(i + 1) : null;
				int a = g(grid, s.c0(), s.r0(), outside, n);
				int b = g(grid, s.c1(), s.r0(), outside, n);
				int c = g(grid, s.c1(), s.r1(), outside, n);
				int e = g(grid, s.c0(), s.r1(), outside, n);
				tri(a, b, c, n);
				if (next != null && next.c0() > s.c0()) {
					int d = g(grid, next.c0(), s.r1(), outside, n);
					tri(a, c, d, n);
					tri(a, d, e, n);
				} else {
					tri(a, c, e, n);
				}
			}
		}

		int g(Map<Long, Integer> grid, int c, int r, boolean outside, double[] n) {
			long key = (long) c << 32 | r;
			Integer i = grid.get(key);
			if (i == null) {
				i = vertex(at(c, r, outside), outside ? frontUv(c, r) : backUv(c, r), n);
				grid.put(key, i);
			}
			return i;
		}
	}

	private static Mesh build() {
		Builder b = new Builder();
		b.face(true);
		int outside = b.indices.size();
		b.face(false);
		int inside = b.indices.size() - outside;
		for (Edge e : edges()) {
			double[] n = switch (e.side()) {
				case OUTER -> new double[] {1, 0, 0};
				case INNER -> new double[] {-1, 0, 0};
				case TOP -> new double[] {0, -1, 0};
				case BOTTOM -> new double[] {0, 1, 0};
			};
			double[] uvA = {e.uv()[0], e.uv()[1]};
			double[] uvB = {e.uv()[2], e.uv()[3]};
			int a0 = b.vertex(at(e.ca(), e.ra(), false), uvA, n);
			int b0 = b.vertex(at(e.cb(), e.rb(), false), uvB, n);
			int b1 = b.vertex(at(e.cb(), e.rb(), true), uvB, n);
			int a1 = b.vertex(at(e.ca(), e.ra(), true), uvA, n);
			b.tri(a0, b0, b1, n);
			b.tri(a0, b1, a1, n);
		}
		int leftCount = b.indices.size();
		int edge = leftCount - outside - inside;
		int leftVertices = b.vertices.size();
		// The right wing: the left one mirrored in x (positions and normals), the same texture coordinates, reversed winding.
		for (int i = 0; i < leftVertices; i++) {
			double[] v = b.vertices.get(i);
			b.vertices.add(new double[] {-v[0], v[1], v[2], v[3], v[4], -v[5], v[6], v[7]});
		}
		for (int t = 0; t < leftCount; t += 3) {
			b.indices.add(b.indices.get(t) + leftVertices);
			b.indices.add(b.indices.get(t + 2) + leftVertices);
			b.indices.add(b.indices.get(t + 1) + leftVertices);
		}
		float[] vertices = new float[b.vertices.size() * FLOATS_PER_VERTEX];
		for (int i = 0; i < b.vertices.size(); i++) {
			double[] v = b.vertices.get(i);
			for (int k = 0; k < FLOATS_PER_VERTEX; k++) {
				vertices[i * FLOATS_PER_VERTEX + k] = (float) v[k];
			}
		}
		int[] indices = b.indices.stream().mapToInt(Integer::intValue).toArray();
		return new Mesh(vertices, indices, leftCount, outside, inside, edge);
	}

	/** One wing's triangles as Minecraft quads (see {@link #wingQuads}). */
	private static float[] quads(Mesh mesh, boolean right) {
		int first = right ? mesh.leftCount() : 0;
		int triangles = mesh.leftCount() / 3;
		float[] out = new float[triangles * 4 * FLOATS_PER_VERTEX];
		float[] v = mesh.vertices();
		int[] idx = mesh.indices();
		int o = 0;
		for (int t = 0; t < triangles; t++) {
			int a = idx[first + t * 3];
			int b = idx[first + t * 3 + 1];
			int c = idx[first + t * 3 + 2];
			// Reversed (a, c, b), the last corner repeated: counter-clockwise from outside.
			for (int corner : new int[] {a, c, b, b}) {
				int s = corner * FLOATS_PER_VERTEX;
				out[o++] = v[s] / 16f;
				out[o++] = v[s + 1] / 16f;
				out[o++] = v[s + 2] / 16f;
				out[o++] = v[s + 3] / 64f;
				out[o++] = v[s + 4] / 32f;
				out[o++] = v[s + 5];
				out[o++] = v[s + 6];
				out[o++] = v[s + 7];
			}
		}
		return out;
	}
}
