package com.flintclient.client.cosmetics;

import static com.flintclient.client.cosmetics.Placeholders.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Leaving out the faces of solid boxes turned away from the eye ({@link HiddenFaces}) changes nothing on screen: for eyes
 * anywhere near the origin (within {@link HiddenFaces#MARGIN}, as view bobbing moves it) and models placed, turned and
 * stretched at random, every ray from the eye meets the same face first with and without the faces left out.
 */
class HiddenFacesTest {
	private static final String ROTATED = """
		{"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.flint_test_turned",
		  "texture_width": 32, "texture_height": 32},
		  "bones": [{"name": "root", "pivot": [0, 24, 0], "rotation": [10, 25, -5],
		    "cubes": [{"origin": [-3, 24, -3], "size": [6, 5, 6], "uv": [0, 0]},
		      {"origin": [-1, 29, -1], "size": [2, 3, 2], "uv": [0, 12], "inflate": 0.25, "pivot": [0, 30, 0], "rotation": [0, 45, 30]},
		      {"origin": [4, 24, -1], "size": [3, 3, 0], "uv": [0, 20]}]}]}]}""";

	private static CosmeticModel solid(String geometry) throws CosmeticLimits.Refused {
		CosmeticModel m = CosmeticModel.bake(CosmeticGeometry.parse(bytes(geometry)), CosmeticModel.HEAD, false);
		int[] argb = new int[m.textureWidth * m.textureHeight];
		Arrays.fill(argb, 0xFF808080);
		m.markSolidBoxes(argb, m.textureWidth, m.textureHeight, 1);
		return m;
	}

	@Test
	void boxesAreFoundAndPlatesAreNot() throws CosmeticLimits.Refused {
		assertNull(solid(Placeholders.WINGS).solid, "flat wings have no solid box");
		CosmeticModel turned = solid(ROTATED);
		int boxes = 0;
		for (byte b : turned.solid) {
			boxes += b;
		}
		assertEquals(2, boxes, "the two closed cubes, not the plate");
		// A hole in one face's texture: that box draws every face.
		CosmeticModel m = CosmeticModel.bake(CosmeticGeometry.parse(bytes(ROTATED)), CosmeticModel.HEAD, false);
		int[] argb = new int[m.textureWidth * m.textureHeight];
		Arrays.fill(argb, 0xFF808080);
		argb[6 * m.textureWidth + 3] = 0x10FFFFFF;
		m.markSolidBoxes(argb, m.textureWidth, m.textureHeight, 1);
		int left = 0;
		for (byte b : m.solid) {
			left += b;
		}
		assertEquals(1, left);
	}

	@Test
	void nothingOnScreenChanges() throws CosmeticLimits.Refused {
		Random random = new Random(1234);
		int culled = 0;
		int total = 0;
		for (String g : new String[] {ROTATED, Placeholders.HEADWEAR, Placeholders.BODYWEAR, Placeholders.PET, Placeholders.GLOVES}) {
			CosmeticModel m = solid(g);
			if (m.solid == null) {
				continue;
			}
			float[] bones = new ModelPose(m, null).bone;
			for (int trial = 0; trial < 120; trial++) {
				float[] pose = randomPose(random, trial % 3 == 0);
				float[] eye = {gauss(random, 0.1F), gauss(random, 0.1F), gauss(random, 0.1F)};
				float len = (float) Math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]);
				if (len > HiddenFaces.MARGIN * 0.99F) {
					for (int k = 0; k < 3; k++) {
						eye[k] *= HiddenFaces.MARGIN * 0.99F / len;
					}
				}
				float[][] world = new float[m.quadCount][];
				boolean[] keep = new boolean[m.quadCount];
				float[] c = new float[12];
				float[] inv = new float[HiddenFaces.SIZE];
				for (int b = 0; b < m.bones; b++) {
					Mat.mul(pose, 0, bones, b * 12, c, 0);
					boolean hide = HiddenFaces.invert(c, 0, inv, 0);
					for (int q = m.quadStart[b]; q < m.quadStart[b + 1]; q++) {
						world[q] = new float[12];
						for (int i = 0; i < 4; i++) {
							int v = q * CosmeticModel.QUAD + i * 5;
							float x = m.quads[v];
							float y = m.quads[v + 1];
							float z = m.quads[v + 2];
							world[q][i * 3] = c[0] * x + c[3] * y + c[6] * z + c[9];
							world[q][i * 3 + 1] = c[1] * x + c[4] * y + c[7] * z + c[10];
							world[q][i * 3 + 2] = c[2] * x + c[5] * y + c[8] * z + c[11];
						}
						keep[q] = true;
					}
					for (int q = m.quadStart[b]; q < m.quadStart[b + 1]; q++) {
						if (hide && m.solid[q] != 0) {
							int mask = HiddenFaces.faces(m, q, inv, 0);
							for (int i = 0; i < 6; i++) {
								keep[q + i] = (mask & 1 << i) != 0;
								culled += keep[q + i] ? 0 : 1;
							}
							q += 5;
						}
					}
				}
				total += m.quadCount;
				for (int ray = 0; ray < 300; ray++) {
					// Toward a random point on a random face (rays that miss everything prove nothing).
					float[] f = world[random.nextInt(m.quadCount)];
					float a = random.nextFloat();
					float bb = random.nextFloat() * (1 - a);
					float[] target = new float[3];
					for (int k = 0; k < 3; k++) {
						target[k] = f[k] + a * (f[3 + k] - f[k]) + bb * (f[6 + k] - f[k]);
					}
					float[] dir = {target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]};
					double all = Double.MAX_VALUE;
					double kept = Double.MAX_VALUE;
					for (int q = 0; q < m.quadCount; q++) {
						double t = hit(eye, dir, world[q]);
						all = Math.min(all, t);
						if (keep[q]) {
							kept = Math.min(kept, t);
						}
					}
					assertEquals(all, kept, 1e-4, "a ray meets a face left out first (trial " + trial + ")");
				}
			}
		}
		assertTrue(culled > total / 5, "faces are left out: " + culled + " of " + total);
	}

	@Test
	void onlyPartlyTransparentFacesAreBlended() throws CosmeticLimits.Refused {
		CosmeticModel m = CosmeticModel.bake(CosmeticGeometry.parse(bytes(ROTATED)), CosmeticModel.HEAD, false);
		int[] argb = new int[m.textureWidth * m.textureHeight];
		Arrays.fill(argb, 0xFF808080);
		// A see-through pixel (not drawn) changes nothing; a half-transparent one on the big cube's west face needs blending.
		argb[0] = 0x00000000;
		argb[6 * m.textureWidth + 3] = 0x80FFFFFF;
		assertEquals(1, m.markBlended(argb, m.textureWidth, m.textureHeight, 1));
		assertTrue(m.blendMarked);
		int blended = -1;
		for (int q = 0; q < m.quadCount; q++) {
			if (m.blend[q] != 0) {
				blended = q;
			}
		}
		assertTrue(blended >= 0 && blended < 6, "a face of the first cube");
		// Blended texture: a box counts as solid only with every pixel fully opaque, so the first cube isn't one.
		m.markSolidBoxes(argb, m.textureWidth, m.textureHeight, 1, 255);
		assertEquals(0, m.solid[0]);
		assertEquals(1, m.solid[6]);
	}

	@Test
	void insideABoxEveryFaceDraws() throws CosmeticLimits.Refused {
		CosmeticModel m = solid(ROTATED);
		float[] bones = new ModelPose(m, null).bone;
		float[] inv = new float[HiddenFaces.SIZE];
		// The eye at the big cube's centre: identity pose, the model moved so that centre is at the origin.
		float[] pose = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
		float[] c = new float[12];
		Mat.mul(pose, 0, bones, 0, c, 0);
		float cx = 0;
		float cy = 0;
		float cz = 0;
		for (int i = 0; i < 24; i++) {
			int v = (i / 4) * CosmeticModel.QUAD + (i % 4) * 5;
			cx += (c[0] * m.quads[v] + c[3] * m.quads[v + 1] + c[6] * m.quads[v + 2] + c[9]) / 24;
			cy += (c[1] * m.quads[v] + c[4] * m.quads[v + 1] + c[7] * m.quads[v + 2] + c[10]) / 24;
			cz += (c[2] * m.quads[v] + c[5] * m.quads[v + 1] + c[8] * m.quads[v + 2] + c[11]) / 24;
		}
		c[9] -= cx;
		c[10] -= cy;
		c[11] -= cz;
		assertTrue(HiddenFaces.invert(c, 0, inv, 0));
		assertEquals(0x3F, HiddenFaces.faces(m, 0, inv, 0));
	}

	private static float gauss(Random r, float s) {
		return (float) r.nextGaussian() * s;
	}

	/** A random turn, stretch (sometimes uneven) and placement 0.6 to 6 blocks away. */
	private static float[] randomPose(Random r, boolean uneven) {
		float[] rot = Mat.rotationZYX(r.nextFloat() * 6.3F, r.nextFloat() * 6.3F, r.nextFloat() * 6.3F);
		float sx = uneven ? 0.5F + r.nextFloat() * 1.5F : 1;
		float sy = uneven ? 0.5F + r.nextFloat() * 1.5F : 1;
		float sz = uneven ? 0.5F + r.nextFloat() * 1.5F : 1;
		float[] out = new float[12];
		for (int k = 0; k < 3; k++) {
			out[k] = rot[k] * sx;
			out[3 + k] = rot[3 + k] * sy;
			out[6 + k] = rot[6 + k] * sz;
		}
		float dist = 0.6F + r.nextFloat() * 5.4F;
		float[] dir = {gauss(r, 1), gauss(r, 1), gauss(r, 1)};
		float len = (float) Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
		for (int k = 0; k < 3; k++) {
			out[9 + k] = dir[k] / len * dist;
		}
		return out;
	}

	/** Where the ray {@code o + t·d} meets the quad (12 floats), or MAX_VALUE. */
	private static double hit(float[] o, float[] d, float[] quad) {
		return Math.min(triangle(o, d, quad, 0, 3, 6), triangle(o, d, quad, 0, 6, 9));
	}

	private static double triangle(float[] o, float[] d, float[] p, int a, int b, int c) {
		double e1x = p[b] - p[a];
		double e1y = p[b + 1] - p[a + 1];
		double e1z = p[b + 2] - p[a + 2];
		double e2x = p[c] - p[a];
		double e2y = p[c + 1] - p[a + 1];
		double e2z = p[c + 2] - p[a + 2];
		double hx = d[1] * e2z - d[2] * e2y;
		double hy = d[2] * e2x - d[0] * e2z;
		double hz = d[0] * e2y - d[1] * e2x;
		double det = e1x * hx + e1y * hy + e1z * hz;
		if (Math.abs(det) < 1e-12) {
			return Double.MAX_VALUE;
		}
		double inv = 1 / det;
		double sx = o[0] - p[a];
		double sy = o[1] - p[a + 1];
		double sz = o[2] - p[a + 2];
		double u = (sx * hx + sy * hy + sz * hz) * inv;
		if (u < -1e-9 || u > 1 + 1e-9) {
			return Double.MAX_VALUE;
		}
		double qx = sy * e1z - sz * e1y;
		double qy = sz * e1x - sx * e1z;
		double qz = sx * e1y - sy * e1x;
		double v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
		if (v < -1e-9 || u + v > 1 + 1e-9) {
			return Double.MAX_VALUE;
		}
		double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
		return t > 1e-6 ? t : Double.MAX_VALUE;
	}
}
