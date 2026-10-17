package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.SkinVoxels.Face;
import com.flintclient.client.control.feature.SkinVoxels.Voxel;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkinVoxelsTest {
	@Test
	void transparentLayerMakesNothing() {
		assertEquals(0, SkinVoxels.build(SkinVoxels.JACKET, (x, y) -> false, 1, 1).size());
	}

	@Test
	void fullLayerHasOneVoxelPerTexel() {
		List<Voxel> hat = SkinVoxels.build(SkinVoxels.HAT, (x, y) -> true, 1, 1);
		assertEquals(6 * 64, hat.size());
		List<Voxel> jacket = SkinVoxels.build(SkinVoxels.JACKET, (x, y) -> true, 1, 1);
		assertEquals(2 * 8 * 12 + 2 * 4 * 12 + 2 * 8 * 4, jacket.size());
	}

	@Test
	void theFrontTexelSitsOnTheFrontFaceAndSticksOut() {
		// Only the jacket's front-face texel at its top-left (u = 16 + 4, v = 32 + 4): x = -4..-3, y = 0..1, at z = -2.
		List<Voxel> one = SkinVoxels.build(SkinVoxels.JACKET, (x, y) -> x == 20 && y == 36, 1, 1);
		assertEquals(1, one.size());
		Voxel v = one.get(0);
		assertEquals(-4F, v.minX(), 1e-6);
		assertEquals(-3F, v.maxX(), 1e-6);
		assertEquals(0F, v.minY(), 1e-6);
		assertEquals(-3F, v.minZ(), 1e-6, "one unit out in front (north)");
		assertEquals(20.5F, v.u(), 1e-6);
		assertEquals(36.5F, v.v(), 1e-6);
		assertTrue(v.has(Face.NORTH));
		assertFalse(v.has(Face.SOUTH), "the face into the body is left out");
	}

	@Test
	void facesBetweenNeighboursAreLeftOut() {
		// Two texels side by side on the jacket front.
		List<Voxel> two = SkinVoxels.build(SkinVoxels.JACKET, (x, y) -> (x == 20 || x == 21) && y == 36, 1, 1);
		assertEquals(2, two.size());
		Voxel left = two.get(0).minX() < two.get(1).minX() ? two.get(0) : two.get(1);
		Voxel right = left == two.get(0) ? two.get(1) : two.get(0);
		assertFalse(left.has(Face.EAST));
		assertFalse(right.has(Face.WEST));
		assertTrue(left.has(Face.WEST));
		assertTrue(left.has(Face.DOWN) && left.has(Face.UP));
	}

	@Test
	void sidesMapTheWayMinecraftDoes() {
		// The head's top texel row nearest the front (u = 32 + 8.., v = 0 + 7: the last row) is at the front (z near -4).
		Voxel top = SkinVoxels.build(SkinVoxels.HAT, (x, y) -> x == 40 && y == 7, 1, 1).get(0);
		assertEquals(-8F, top.maxY(), 0.1F);
		assertTrue(top.minZ() < -3.4F, "front row: " + top.minZ());
		// The right side (WEST face) texel nearest the front: the face net's right end (u = 32 + 7) is at z near -4.
		Voxel side = SkinVoxels.build(SkinVoxels.HAT, (x, y) -> x == 39 && y == 8, 1, 1).get(0);
		assertTrue(side.minX() < -4.5F);
		assertTrue(side.minZ() < -3.4F, "front edge: " + side.minZ());
		// The left side (EAST face) texel at its net's left end (u = 32 + 16) is at the front too.
		Voxel left = SkinVoxels.build(SkinVoxels.HAT, (x, y) -> x == 48 && y == 8, 1, 1).get(0);
		assertTrue(left.maxX() > 4.5F);
		assertTrue(left.minZ() < -3.4F, "front edge: " + left.minZ());
	}
}
