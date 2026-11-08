package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.flintclient.client.control.feature.CapeElytra.Face;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The cape-derived elytra, mapping "cape-derived-elytra" v2 (Minecraft's own elytra with the cape's texture), against the shared
 * goldens (handoff cape-elytra-v2: goldens.json and cape-elytra-v2.mjs, which the websites' previews port), Minecraft's own model
 * and texture, and what the renderer is given.
 */
class CapeElytraTest {
	/** goldens.json GEOMETRY_SHA256: Float32 little-endian, the left wing's quads then the right wing's. */
	private static final String GEOMETRY_SHA256 = "e0fe2fdaca8634a1586333a80fe0a644048739e2d821b800ed9afafd495554ae";
	private static final int F = CapeElytra.FLOATS_PER_VERTEX;

	private static int quads(float[] q) {
		return q.length / (4 * F);
	}

	private static Face face(float[] q, int quad) {
		int i = quad * 4 * F;
		for (Face f : Face.values()) {
			if (f.nx == q[i + 5] && f.ny == q[i + 6] && f.nz == q[i + 7]) {
				return f;
			}
		}
		throw new AssertionError("no face for quad " + quad);
	}

	@Test
	void isVersionTwoOfTheMapping() {
		assertEquals("cape-derived-elytra", CapeElytra.MAPPING);
		assertEquals(2, CapeElytra.VERSION);
		assertEquals(8, CapeElytra.FLOATS_PER_VERTEX);
	}

	@Test
	void matchesTheSharedGoldens() throws Exception {
		float[] left = CapeElytra.wingQuads(false);
		float[] right = CapeElytra.wingQuads(true);
		ByteBuffer bytes = ByteBuffer.allocate((left.length + right.length) * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : left) {
			bytes.putFloat(v);
		}
		for (float v : right) {
			bytes.putFloat(v);
		}
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
		assertEquals(GEOMETRY_SHA256, hash);
		assertEquals(43, quads(left));
		assertEquals(43, quads(right));
	}

	@Test
	void drawsOnlyWhatMinecraftsElytraDraws() {
		// Per wing: 20 rows outside, the +x side's upper 9 rows, the −x side's lower 11, 2 runs on top, 1 below, nothing inside.
		Map<Face, Integer> left = new TreeMap<>();
		float[] q = CapeElytra.wingQuads(false);
		for (int i = 0; i < quads(q); i++) {
			left.merge(face(q, i), 1, Integer::sum);
		}
		assertEquals(Map.of(Face.SOUTH, 20, Face.EAST, 9, Face.WEST, 11, Face.DOWN, 2, Face.UP, 1), left);
		Map<Face, Integer> right = new TreeMap<>();
		float[] r = CapeElytra.wingQuads(true);
		for (int i = 0; i < quads(r); i++) {
			right.merge(face(r, i), 1, Integer::sum);
		}
		// The mirrored wing: its x-facing faces swap sides, as Minecraft mirrors their normals.
		assertEquals(Map.of(Face.SOUTH, 20, Face.WEST, 9, Face.EAST, 11, Face.DOWN, 2, Face.UP, 1), right);
	}

	@Test
	void isMinecraftsElytraBox() {
		// ElytraModel: left box (-10,0,0)+(10,20,2) inflated by 1; in blocks (/16).
		float[] q = CapeElytra.wingQuads(false);
		for (int i = 0; i < q.length; i += F) {
			assertTrue(q[i] >= -11 / 16f - 1e-6 && q[i] <= 1 / 16f + 1e-6, "x " + q[i]);
			assertTrue(q[i + 1] >= -1 / 16f - 1e-6 && q[i + 1] <= 21 / 16f + 1e-6, "y " + q[i + 1]);
			assertTrue(q[i + 2] >= -1 / 16f - 1e-6 && q[i + 2] <= 3 / 16f + 1e-6, "z " + q[i + 2]);
		}
		// The outside is the box's +z face, 3 px behind the pivot, and spans the box's full width and height.
		float minX = 1, maxX = -1, minY = 2, maxY = -1;
		for (int k = 0; k < quads(q); k++) {
			if (face(q, k) != Face.SOUTH) {
				continue;
			}
			for (int c = 0; c < 4; c++) {
				int i = (k * 4 + c) * F;
				assertEquals(3 / 16f, q[i + 2], 1e-6);
				minX = Math.min(minX, q[i]);
				maxX = Math.max(maxX, q[i]);
				minY = Math.min(minY, q[i + 1]);
				maxY = Math.max(maxY, q[i + 1]);
			}
		}
		assertEquals(-11 / 16f, minX, 1e-6);
		assertEquals(1 / 16f, maxX, 1e-6);
		assertEquals(-1 / 16f, minY, 1e-6);
		assertEquals(21 / 16f, maxY, 1e-6);
	}

	@Test
	void theRightWingIsTheLeftMirrored() {
		float[] l = CapeElytra.wingQuads(false);
		float[] r = CapeElytra.wingQuads(true);
		assertEquals(l.length, r.length);
		// Same faces in the same order, each quad mirrored in x with its corners reversed (Minecraft's mirror), same texture.
		for (int k = 0; k < quads(l); k++) {
			for (int c = 0; c < 4; c++) {
				int li = (k * 4 + c) * F;
				int ri = (k * 4 + (3 - c)) * F;
				assertEquals(-l[li], r[ri], 1e-6);
				assertEquals(l[li + 1], r[ri + 1], 1e-6);
				assertEquals(l[li + 2], r[ri + 2], 1e-6);
				assertEquals(l[li + 3], r[ri + 3], 1e-6);
				assertEquals(l[li + 4], r[ri + 4], 1e-6);
				assertEquals(-l[li + 5], r[ri + 5], 1e-6);
			}
		}
	}

	@Test
	void theOutsideIsTheCapesFrontAsWorn() {
		// The front image (1,1)-(11,17) of the 64×32 net, unmirrored seen from behind: its column 0 at the wing's +x edge
		// (the player's left, the viewer's left from behind), row 0 at the top; the whole image, 16 rows over the wing's 20.
		float[] q = CapeElytra.wingQuads(false);
		double minU = 64, maxU = 0, minV = 32, maxV = 0;
		for (int k = 0; k < quads(q); k++) {
			if (face(q, k) != Face.SOUTH) {
				continue;
			}
			for (int c = 0; c < 4; c++) {
				int i = (k * 4 + c) * F;
				double u = q[i + 3] * 64;
				double v = q[i + 4] * 32;
				// x = +1/16 (the +x edge) is column 0; x = -11/16 is column 10.
				assertEquals(1 + (1 / 16.0 - q[i]) * 16 / 12 * 10, u, 1e-3);
				// y = -1/16 (the top) is row 0; y = 21/16 is row 16.
				assertEquals(1 + (q[i + 1] + 1 / 16.0) * 16 / 22 * 16, v, 1e-3);
				minU = Math.min(minU, u);
				maxU = Math.max(maxU, u);
				minV = Math.min(minV, v);
				maxV = Math.max(maxV, v);
			}
		}
		assertEquals(1, minU, 1e-4);
		assertEquals(11, maxU, 1e-4);
		assertEquals(1, minV, 1e-4);
		assertEquals(17, maxV, 1e-4);
	}

	@Test
	void theSidesReadTheCapesSidesAndNothingReadsTheLining() {
		float[] q = CapeElytra.wingQuads(false);
		for (int k = 0; k < quads(q); k++) {
			Face f = face(q, k);
			for (int c = 0; c < 4; c++) {
				int i = (k * 4 + c) * F;
				double u = q[i + 3] * 64;
				double v = q[i + 4] * 32;
				switch (f) {
					case WEST -> assertTrue(u >= 11 - 1e-4 && u <= 12 + 1e-4 && v >= 1 - 1e-4 && v <= 17 + 1e-4, "west " + u + "," + v);
					case EAST -> assertTrue(u >= -1e-4 && u <= 1 + 1e-4 && v >= 1 - 1e-4 && v <= 17 + 1e-4, "east " + u + "," + v);
					case DOWN -> assertTrue(u >= 1 - 1e-4 && u <= 11 + 1e-4 && v >= -1e-4 && v <= 1 + 1e-4, "top " + u + "," + v);
					case UP -> assertTrue(u >= 11 - 1e-4 && u <= 21 + 1e-4 && v >= -1e-4 && v <= 1 + 1e-4, "bottom " + u + "," + v);
					case SOUTH -> assertTrue(u >= 1 - 1e-4 && u <= 11 + 1e-4, "front " + u);
					case NORTH -> throw new AssertionError("Minecraft's elytra draws nothing inside");
				}
			}
		}
	}

	@Test
	void theMaskIsMinecraftsElytraTexture() throws Exception {
		// The game's own elytra texture, where this line's test class path has it (26.x and 1.21.1 name it differently).
		BufferedImage image = null;
		for (String path : new String[] {"assets/minecraft/textures/entity/equipment/wings/elytra.png", "assets/minecraft/textures/entity/elytra.png"}) {
			try (InputStream in = CapeElytraTest.class.getClassLoader().getResourceAsStream(path)) {
				if (in != null) {
					image = ImageIO.read(in);
					break;
				}
			}
		}
		if (image == null) {
			return; // Minecraft's assets aren't on this line's test class path; the goldens still pin the mask.
		}
		assertEquals(64, image.getWidth());
		assertEquals(32, image.getHeight());
		for (int y = 0; y < 22; y++) {
			for (int x = 22; x < 46; x++) {
				boolean opaque = (image.getRGB(x, y) >>> 24) > 0;
				assertEquals(opaque, CapeElytra.drawn(x, y), "texel " + x + "," + y);
			}
		}
	}

	@Test
	void texturesAreMinecraftsCubeRectangles() {
		// ModelPart.Cube at texOffs(22, 0), 10×20×2 (the elytra) and texOffs(0, 0), 10×16×1 (the cape, worn turned half round).
		assertArrayEquals(new int[] {36, 2, 46, 22}, CapeElytra.rect(Face.SOUTH, 22, 0, 10, 20, 2));
		assertArrayEquals(new int[] {24, 2, 34, 22}, CapeElytra.rect(Face.NORTH, 22, 0, 10, 20, 2));
		assertArrayEquals(new int[] {1, 1, 11, 17}, CapeElytra.capeRect(Face.SOUTH));
		assertArrayEquals(new int[] {12, 1, 22, 17}, CapeElytra.capeRect(Face.NORTH));
		assertArrayEquals(new int[] {11, 1, 12, 17}, CapeElytra.capeRect(Face.WEST));
		assertArrayEquals(new int[] {0, 1, 1, 17}, CapeElytra.capeRect(Face.EAST));
		assertArrayEquals(new int[] {1, 0, 11, 1}, CapeElytra.capeRect(Face.DOWN));
		assertArrayEquals(new int[] {11, 1, 21, 0}, CapeElytra.capeRect(Face.UP));
	}

	@Test
	void usesTheCapeOnlyWhenEverythingAgrees() {
		assertTrue(CapeElytra.use(true, true, true, false, true));
		assertFalse(CapeElytra.use(false, true, true, false, true));
		assertFalse(CapeElytra.use(true, false, true, false, true));
		assertFalse(CapeElytra.use(true, true, false, false, true));
		assertFalse(CapeElytra.use(true, true, true, true, true));
		assertFalse(CapeElytra.use(true, true, true, false, false));
	}
}
