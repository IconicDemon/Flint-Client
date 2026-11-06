package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.SkinVoxels.Face;
import com.flintclient.client.control.feature.SkinVoxels.Mesh;
import org.junit.jupiter.api.Test;

class SkinVoxelsTest {
	private static final float S = 1.15F;

	private static Mesh build(SkinVoxels.Layer layer, SkinVoxels.Alpha alpha) {
		return SkinVoxels.build(layer, alpha, S);
	}

	@Test
	void transparentLayerMakesNothing() {
		assertEquals(0, build(SkinVoxels.JACKET, (x, y) -> 0).quads());
	}

	@Test
	void fullLayerIsOneQuadPerFace() {
		// Every cell filled: no side shows anywhere, and the six faces are a quad each (not one box per texel).
		assertEquals(6, build(SkinVoxels.HAT, (x, y) -> 255).quads());
		assertEquals(6, build(SkinVoxels.JACKET, (x, y) -> 255).quads());
		assertEquals(6, build(SkinVoxels.RIGHT_SLEEVE_SLIM, (x, y) -> 255).quads());
	}

	@Test
	void faintTexelsAreLeftOut() {
		// Below a tenth of full alpha Minecraft's shader drops a texel: it makes no box.
		assertEquals(0, build(SkinVoxels.HAT, (x, y) -> 20).quads());
	}

	@Test
	void theFaceQuadIsTexturedAsMinecraftsBox() {
		Mesh m = build(SkinVoxels.JACKET, (x, y) -> x == 20 && y == 36 ? 255 : 0);
		int north = -1;
		for (int q = 0; q < m.quads(); q++) {
			if (m.face(q) == Face.NORTH.ordinal() && Math.abs(m.x(q, 0) - m.x(q, 1)) > 0.4F) {
				north = q;
			}
		}
		assertTrue(north >= 0, "the front face's quad");
		// Corner 0 is the box's max x, min y: the net's (u + d + w, v + d) = (28, 36).
		assertEquals(28 / 64F, m.u(north, 0), 1e-6);
		assertEquals(36 / 64F, m.v(north, 0), 1e-6);
		assertEquals(20 / 64F, m.u(north, 1), 1e-6);
		// Scaled about the centre: the front stands 2 * 1.15 out from the middle (z = 0), in blocks.
		assertEquals(-2 * S / 16, m.z(north, 0), 1e-5);
		assertEquals(4 * S / 16, m.x(north, 0), 1e-5);
	}

	@Test
	void aLoneTexelShowsItsSidesAndBack() {
		// The jacket's front top-left texel: its face, four sides and (past the body's edge) its back.
		Mesh m = build(SkinVoxels.JACKET, (x, y) -> x == 20 && y == 36 ? 255 : 0);
		assertEquals(6, m.quads());
		// The sides are read at the texel's middle, never on the line between two texels.
		for (int q = 0; q < m.quads(); q++) {
			for (int k = 0; k < 4; k++) {
				assertTrue(m.u(q, k) >= 20 / 64F - 1e-6 && m.u(q, k) <= 28 / 64F + 1e-6);
			}
		}
	}

	@Test
	void sidesAlongARowAreMerged() {
		// A row of six texels on the jacket front, with nothing around: the top and bottom sides are one strip each.
		Mesh row = build(SkinVoxels.JACKET, (x, y) -> y == 40 && x >= 21 && x <= 26 ? 255 : 0);
		int downs = 0;
		int ups = 0;
		for (int q = 0; q < row.quads(); q++) {
			downs += row.face(q) == Face.DOWN.ordinal() ? 1 : 0;
			ups += row.face(q) == Face.UP.ordinal() ? 1 : 0;
		}
		assertEquals(1, downs);
		assertEquals(1, ups);
		// The face, the two strips and the two ends.
		assertEquals(5, row.quads());
	}

	@Test
	void anEdgeTexelSharedByTwoFacesIsSplitNotDoubled() {
		// The hat's front-left column (NORTH texels at the left edge) and the right side's front column (WEST at its front
		// edge) fill the same cells: their top and bottom sides are halved along the diagonal, one half each.
		Mesh m = build(SkinVoxels.HAT, (x, y) -> x == 40 && y == 10 || x == 39 && y == 10 ? 255 : 0);
		int triangles = 0;
		for (int q = 0; q < m.quads(); q++) {
			float[] p = SkinVoxels.quad(m, q);
			boolean repeated = p[6] == p[9] && p[7] == p[10] && p[8] == p[11];
			triangles += repeated ? 1 : 0;
		}
		assertEquals(4, triangles, "two sides, two halves each");
	}

	@Test
	void sidesInsideTheBodyAreLeftOut() {
		// A filled ring in the middle of the hat's front: the backs of those cells are inside the head.
		Mesh m = build(SkinVoxels.HAT, (x, y) -> x >= 42 && x <= 45 && y >= 10 && y <= 13 ? 255 : 0);
		for (int q = 0; q < m.quads(); q++) {
			assertTrue(m.face(q) != Face.SOUTH.ordinal(), "no quad looks into the head");
		}
	}

	@Test
	void aShellNeverLiesOnTheBody() {
		// Asked for no scale at all, the shell still stands a little out of the body.
		Mesh m = SkinVoxels.build(SkinVoxels.HAT, (x, y) -> 255, 1.0F);
		for (int q = 0; q < m.quads(); q++) {
			if (m.face(q) == Face.NORTH.ordinal()) {
				assertTrue(m.z(q, 0) < -4.05F / 16);
			}
		}
	}
}
