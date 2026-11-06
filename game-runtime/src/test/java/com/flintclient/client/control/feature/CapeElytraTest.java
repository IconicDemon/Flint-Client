package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.flintclient.client.control.feature.CapeElytra.Edge;
import com.flintclient.client.control.feature.CapeElytra.Mesh;
import com.flintclient.client.control.feature.CapeElytra.Side;
import com.flintclient.client.control.feature.CapeElytra.Step;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The cape-derived elytra against the websites' goldens (mapping "cape-derived-elytra" v1; their tests/cape-elytra.test.ts,
 * the same in the Store, the Command Center and Creator): the texture table, the edges, the mesh (hashed exactly as the
 * websites hash theirs), the mirrored wing, winding, closedness; then what Minecraft is given.
 */
class CapeElytraTest {
	/** tests/cape-elytra.test.ts GEOMETRY_SHA256: the websites' Float32 vertices then Uint16 indices, little-endian. */
	private static final String GEOMETRY_SHA256 = "8333be99693866c8c382755536bcfd37127e233f91fb23f35385e0f153c5a6b5";
	private static final Mesh MESH = CapeElytra.mesh();
	private static final int LEFT_VERTICES = MESH.vertexCount() / 2;

	private static double r4(double x) {
		return Math.round(x * 1e4) / 1e4 + 0.0;
	}

	private static double[] r4(double[] xs) {
		double[] out = new double[xs.length];
		for (int i = 0; i < xs.length; i++) {
			out[i] = r4(xs[i]);
		}
		return out;
	}

	private static float[] vertex(int i) {
		float[] v = new float[CapeElytra.FLOATS_PER_VERTEX];
		System.arraycopy(MESH.vertices(), i * CapeElytra.FLOATS_PER_VERTEX, v, 0, v.length);
		return v;
	}

	@Test
	void isVersionOneOfTheMapping() {
		assertEquals("cape-derived-elytra", CapeElytra.MAPPING);
		assertEquals(1, CapeElytra.VERSION);
		assertArrayEquals(new double[] {1.25, 1.25, 1.25, 0.375, 0.01},
			new double[] {CapeElytra.TEXEL, CapeElytra.THICKNESS, CapeElytra.OUTER_X, CapeElytra.INNER_Z, CapeElytra.UV_INSET});
	}

	@Test
	void stepsAreWholeTexelsNarrowingTowardsTheTip() {
		int[][] want = {{0, 7, 0, 10}, {7, 9, 1, 10}, {9, 10, 2, 10}, {10, 11, 3, 10}, {11, 12, 4, 10}, {12, 13, 5, 10}, {13, 14, 6, 10},
			{14, 15, 7, 10}, {15, 16, 8, 10}};
		int texels = 0;
		for (int i = 0; i < want.length; i++) {
			Step s = CapeElytra.STEPS.get(i);
			assertArrayEquals(want[i], new int[] {s.r0(), s.r1(), s.c0(), s.c1()});
			texels += (s.r1() - s.r0()) * (s.c1() - s.c0());
		}
		assertEquals(123, texels, "123 of the design's 160 texels are on the wing");
	}

	@Test
	void outsideIsTheFrontImageInsideTheBackMirroredThroughTheCloth() {
		double[][] outside = {{1.01, 1.01, 10.99, 8.0012}, {2.008, 8.0012, 10.99, 9.9988}, {3.006, 9.9988, 10.99, 10.9975},
			{4.004, 10.9975, 10.99, 11.9963}, {5.002, 11.9963, 10.99, 12.995}, {6, 12.995, 10.99, 13.9938}, {6.998, 13.9938, 10.99, 14.9925},
			{7.996, 14.9925, 10.99, 15.9913}, {8.994, 15.9913, 10.99, 16.99}};
		double[][] inside = {{21.99, 1.01, 12.01, 8.0012}, {20.992, 8.0012, 12.01, 9.9988}, {19.994, 9.9988, 12.01, 10.9975},
			{18.996, 10.9975, 12.01, 11.9963}, {17.998, 11.9963, 12.01, 12.995}, {17, 12.995, 12.01, 13.9938}, {16.002, 13.9938, 12.01, 14.9925},
			{15.004, 14.9925, 12.01, 15.9913}, {14.006, 15.9913, 12.01, 16.99}};
		for (int i = 0; i < CapeElytra.STEPS.size(); i++) {
			Step s = CapeElytra.STEPS.get(i);
			double[] f0 = CapeElytra.frontUv(s.c0(), s.r0());
			double[] f1 = CapeElytra.frontUv(s.c1(), s.r1());
			double[] b0 = CapeElytra.backUv(s.c0(), s.r0());
			double[] b1 = CapeElytra.backUv(s.c1(), s.r1());
			assertArrayEquals(outside[i], r4(new double[] {f0[0], f0[1], f1[0], f1[1]}), "outside of piece " + (i + 1));
			assertArrayEquals(inside[i], r4(new double[] {b0[0], b0[1], b1[0], b1[1]}), "inside of piece " + (i + 1));
		}
	}

	@Test
	void edgesReadTheLiningsOutermostPixelLine() {
		Object[][] want = {
			{Side.TOP, 0, 0, 10, 0, new double[] {21.99, 1.01, 12.01, 1.01}},
			{Side.INNER, 10, 0, 10, 7, new double[] {12.01, 1.01, 12.01, 8.0012}},
			{Side.INNER, 10, 7, 10, 9, new double[] {12.01, 8.0012, 12.01, 9.9988}},
			{Side.INNER, 10, 9, 10, 10, new double[] {12.01, 9.9988, 12.01, 10.9975}},
			{Side.INNER, 10, 10, 10, 11, new double[] {12.01, 10.9975, 12.01, 11.9963}},
			{Side.INNER, 10, 11, 10, 12, new double[] {12.01, 11.9963, 12.01, 12.995}},
			{Side.INNER, 10, 12, 10, 13, new double[] {12.01, 12.995, 12.01, 13.9938}},
			{Side.INNER, 10, 13, 10, 14, new double[] {12.01, 13.9938, 12.01, 14.9925}},
			{Side.INNER, 10, 14, 10, 15, new double[] {12.01, 14.9925, 12.01, 15.9913}},
			{Side.INNER, 10, 15, 10, 16, new double[] {12.01, 15.9913, 12.01, 16.99}},
			{Side.BOTTOM, 10, 16, 8, 16, new double[] {12.01, 16.99, 14.006, 16.99}},
			{Side.OUTER, 8, 16, 8, 15, new double[] {13.99, 16.99, 13.99, 15.9913}},
			{Side.BOTTOM, 8, 15, 7, 15, new double[] {14.006, 15.99, 15.004, 15.99}},
			{Side.OUTER, 7, 15, 7, 14, new double[] {14.99, 15.9913, 14.99, 14.9925}},
			{Side.BOTTOM, 7, 14, 6, 14, new double[] {15.004, 14.99, 16.002, 14.99}},
			{Side.OUTER, 6, 14, 6, 13, new double[] {15.99, 14.9925, 15.99, 13.9938}},
			{Side.BOTTOM, 6, 13, 5, 13, new double[] {16.002, 13.99, 17, 13.99}},
			{Side.OUTER, 5, 13, 5, 12, new double[] {16.99, 13.9938, 16.99, 12.995}},
			{Side.BOTTOM, 5, 12, 4, 12, new double[] {17, 12.99, 17.998, 12.99}},
			{Side.OUTER, 4, 12, 4, 11, new double[] {17.99, 12.995, 17.99, 11.9963}},
			{Side.BOTTOM, 4, 11, 3, 11, new double[] {17.998, 11.99, 18.996, 11.99}},
			{Side.OUTER, 3, 11, 3, 10, new double[] {18.99, 11.9963, 18.99, 10.9975}},
			{Side.BOTTOM, 3, 10, 2, 10, new double[] {18.996, 10.99, 19.994, 10.99}},
			{Side.OUTER, 2, 10, 2, 9, new double[] {19.99, 10.9975, 19.99, 9.9988}},
			{Side.BOTTOM, 2, 9, 1, 9, new double[] {19.994, 9.99, 20.992, 9.99}},
			{Side.OUTER, 1, 9, 1, 7, new double[] {20.99, 9.9988, 20.99, 8.0012}},
			{Side.BOTTOM, 1, 7, 0, 7, new double[] {20.992, 7.99, 21.99, 7.99}},
			{Side.OUTER, 0, 7, 0, 0, new double[] {21.99, 8.0012, 21.99, 1.01}},
		};
		List<Edge> edges = CapeElytra.edges();
		assertEquals(want.length, edges.size());
		for (int i = 0; i < want.length; i++) {
			Edge e = edges.get(i);
			assertEquals(want[i][0], e.side(), "edge " + i);
			assertArrayEquals(new int[] {(int) want[i][1], (int) want[i][2], (int) want[i][3], (int) want[i][4]}, new int[] {e.ca(), e.ra(), e.cb(), e.rb()},
				"edge " + i);
			assertArrayEquals((double[]) want[i][5], r4(e.uv()), "edge " + i);
		}
		// At every resolution each edge reads the lining's pixel next to it: column 9 − c0 for an outer edge, column 0 for the
		// inner edge, the row above a step's underside, row 0 on top.
		for (int s : new int[] {4, 8, 16, 32}) {
			for (Edge e : edges) {
				double col = Math.floor((e.uv()[0] - 12) * s) / s;
				double row = Math.floor((e.uv()[1] - 1) * s) / s;
				switch (e.side()) {
					case OUTER -> assertEquals(10 - e.ca() - 1.0 / s, col, 1e-9, e + " @" + s);
					case INNER -> assertEquals(0, col, 1e-9);
					case BOTTOM -> assertEquals(e.ra() - 1.0 / s, row, 1e-9, e + " @" + s);
					case TOP -> assertEquals(0, row, 1e-9);
				}
			}
		}
	}

	@Test
	void theMeshIsTheWebsitesByteForByte() throws Exception {
		assertEquals(336, MESH.vertexCount());
		assertEquals(216, MESH.indices().length / 3);
		assertEquals(324, MESH.leftCount());
		assertEquals(78, MESH.outsideCount());
		assertEquals(78, MESH.insideCount());
		assertEquals(168, MESH.edgeCount());
		ByteBuffer vertices = ByteBuffer.allocate(MESH.vertices().length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : MESH.vertices()) {
			vertices.putFloat(f);
		}
		ByteBuffer indices = ByteBuffer.allocate(MESH.indices().length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i : MESH.indices()) {
			assertTrue(i >= 0 && i < 65536);
			indices.putShort((short) i);
		}
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		sha.update(vertices.array());
		sha.update(indices.array());
		assertEquals(GEOMETRY_SHA256, HexFormat.of().formatHex(sha.digest()));
	}

	@Test
	void theLeftWingSpansItsSpecifiedBox() {
		float xmin = Float.MAX_VALUE, xmax = -Float.MAX_VALUE, ymin = Float.MAX_VALUE, ymax = -Float.MAX_VALUE, zmin = Float.MAX_VALUE,
			zmax = -Float.MAX_VALUE;
		for (int i = 0; i < LEFT_VERTICES; i++) {
			float[] v = vertex(i);
			xmin = Math.min(xmin, v[0]);
			xmax = Math.max(xmax, v[0]);
			ymin = Math.min(ymin, v[1]);
			ymax = Math.max(ymax, v[1]);
			zmin = Math.min(zmin, v[2]);
			zmax = Math.max(zmax, v[2]);
		}
		assertArrayEquals(new float[] {-11.25f, 1.25f, 0, 20, 0.375f, 1.625f}, new float[] {xmin, xmax, ymin, ymax, zmin, zmax});
	}

	@Test
	void theRightWingIsTheLeftMirroredWithTheSameTextureAndReversedWinding() {
		for (int i = 0; i < LEFT_VERTICES; i++) {
			float[] l = vertex(i);
			float[] r = vertex(i + LEFT_VERTICES);
			assertArrayEquals(new float[] {-l[0] + 0f, l[1], l[2], l[3], l[4], -l[5] + 0f, l[6], l[7]},
				new float[] {r[0] + 0f, r[1], r[2], r[3], r[4], r[5] + 0f, r[6], r[7]}, "vertex " + i);
		}
		int[] idx = MESH.indices();
		for (int t = 0; t < MESH.leftCount(); t += 3) {
			int f = MESH.leftCount() + t;
			assertArrayEquals(new int[] {idx[t] + LEFT_VERTICES, idx[t + 2] + LEFT_VERTICES, idx[t + 1] + LEFT_VERTICES},
				new int[] {idx[f], idx[f + 1], idx[f + 2]});
		}
	}

	/** (b − a) × (c − a) · the vertex normal: negative for the websites' winding (into the wing). */
	private static double winding(float[] a, float[] b, float[] c) {
		double e1x = b[0] - a[0], e1y = b[1] - a[1], e1z = b[2] - a[2], e2x = c[0] - a[0], e2y = c[1] - a[1], e2z = c[2] - a[2];
		double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
		return nx * a[5] + ny * a[6] + nz * a[7];
	}

	@Test
	void everyTriangleIsWoundAsTheWebsitesWindThem() {
		int[] idx = MESH.indices();
		for (int t = 0; t < idx.length; t += 3) {
			assertTrue(winding(vertex(idx[t]), vertex(idx[t + 1]), vertex(idx[t + 2])) < 0, "triangle " + t / 3);
		}
	}

	@Test
	void eachWingIsClosed() {
		int[] idx = MESH.indices();
		for (int[] range : new int[][] {{0, MESH.leftCount()}, {MESH.leftCount(), MESH.leftCount()}}) {
			Map<String, Integer> edges = new HashMap<>();
			for (int t = range[0]; t < range[0] + range[1]; t += 3) {
				String[] keys = new String[3];
				for (int k = 0; k < 3; k++) {
					float[] v = vertex(idx[t + k]);
					keys[k] = String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f", v[0] + 0f, v[1] + 0f, v[2] + 0f);
				}
				for (int k = 0; k < 3; k++) {
					String a = keys[k], b = keys[(k + 1) % 3];
					edges.merge(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, 1, Integer::sum);
				}
			}
			assertTrue(edges.values().stream().allMatch(n -> n == 2), "every edge shared by exactly two triangles");
		}
	}

	@Test
	void outsideFacesBackwardsInsideForwardsEdgesSideways() {
		int[] idx = MESH.indices();
		Set<String> outside = new HashSet<>(), inside = new HashSet<>(), edges = new HashSet<>();
		for (int i = 0; i < MESH.leftCount(); i++) {
			float[] v = vertex(idx[i]);
			String n = (v[5] + 0f) + "," + (v[6] + 0f) + "," + (v[7] + 0f);
			(i < MESH.outsideCount() ? outside : i < MESH.outsideCount() + MESH.insideCount() ? inside : edges).add(n);
		}
		assertEquals(Set.of("0.0,0.0,1.0"), outside);
		assertEquals(Set.of("0.0,0.0,-1.0"), inside);
		assertEquals(Set.of("-1.0,0.0,0.0", "0.0,-1.0,0.0", "0.0,1.0,0.0", "1.0,0.0,0.0"), edges);
	}

	@Test
	void readsOnlyTheCapesFrontAndBackImages() {
		for (int i = 0; i < MESH.vertexCount(); i++) {
			float[] v = vertex(i);
			boolean front = v[3] > 1 && v[3] < 11 && v[4] > 1 && v[4] < 17;
			boolean back = v[3] > 12 && v[3] < 22 && v[4] > 1 && v[4] < 17;
			assertTrue(front || back, "vertex " + i + " at " + v[3] + ", " + v[4]);
		}
	}

	@Test
	void minecraftGetsEachWingAsQuadsFacingOutAndTexturedFromTheCapeTexture() {
		for (boolean right : new boolean[] {false, true}) {
			float[] q = CapeElytra.wingQuads(right);
			int corners = q.length / CapeElytra.FLOATS_PER_VERTEX;
			assertEquals(108 * 4, corners, "108 triangles a wing, each a quad");
			for (int c = 0; c < corners; c += 4) {
				float[] a = corner(q, c), b = corner(q, c + 1), cc = corner(q, c + 2), d = corner(q, c + 3);
				assertArrayEquals(cc, d, "a triangle's last corner twice");
				// Counter-clockwise from outside for Minecraft: (b − a) × (c − a) points out of the wing.
				assertTrue(winding(a, b, cc) > 0, (right ? "right" : "left") + " quad " + c / 4);
				// Blocks and a 64x32-per-unit texture: inside the cape's front or back image there.
				assertTrue(Math.abs(a[0]) <= 11.25f / 16 + 1e-6 && a[1] >= 0 && a[1] <= 20f / 16 + 1e-6);
				boolean front = a[3] > 1 / 64f && a[3] < 11 / 64f;
				boolean back = a[3] > 12 / 64f && a[3] < 22 / 64f;
				assertTrue((front || back) && a[4] > 1 / 32f && a[4] < 17 / 32f);
			}
		}
		// The same data every time: built once, never per frame or per player.
		assertTrue(CapeElytra.wingQuads(false) == CapeElytra.wingQuads(false));
	}

	private static float[] corner(float[] q, int i) {
		float[] v = new float[CapeElytra.FLOATS_PER_VERTEX];
		System.arraycopy(q, i * CapeElytra.FLOATS_PER_VERTEX, v, 0, v.length);
		return v;
	}

	@Test
	void theCapeDesignIsOnTheElytraOnlyWhenEveryConditionHolds() {
		assertTrue(CapeElytra.use(true, true, true, false, true));
		List<boolean[]> off = new ArrayList<>();
		off.add(new boolean[] {false, true, true, false, true}); // the viewer turned "Cape design on elytra" off
		off.add(new boolean[] {true, false, true, false, true}); // no Flint cape (or not loaded and verified yet)
		off.add(new boolean[] {true, true, false, false, true}); // the wearer hides their cape
		off.add(new boolean[] {true, true, true, true, true}); // Minecraft gives them an elytra texture of their own
		off.add(new boolean[] {true, true, true, false, false}); // another equipment asset's wings
		for (boolean[] o : off) {
			assertFalse(CapeElytra.use(o[0], o[1], o[2], o[3], o[4]), java.util.Arrays.toString(o));
		}
	}
}
