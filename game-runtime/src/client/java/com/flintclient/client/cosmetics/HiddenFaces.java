package com.flintclient.client.cosmetics;

/**
 * The faces of a model's solid boxes ({@link CosmeticModel#solid}) that can't show this frame: seen from outside a closed,
 * convex, fully opaque box, every face turned away from the eye lies behind the box's own faces turned toward it, on every
 * pixel, so leaving it out changes nothing on screen (Minecraft draws entity faces from both sides, and the depth test hides
 * these). The eye is the origin of the space the vertices are written in (Minecraft's camera-relative world, with a
 * perspective projection); a face counts as turned away only when its plane is at least {@link #MARGIN} blocks from the eye
 * on the inside, so an eye moved a little by the projection (view bobbing) still sees exactly what it did. Inside a box, or
 * near its surface, all its faces draw. Pure arithmetic on float arrays, nothing allocated (every line's drawer uses it).
 */
public final class HiddenFaces {
	/** Blocks: how far the eye may be from the space's origin with the answer still exact. */
	public static final float MARGIN = 0.25F;
	/** Floats {@link #invert} writes: the inverse's three rows, the eye in the bone's space, then each row's squared length. */
	public static final int SIZE = 15;

	private HiddenFaces() {
	}

	/**
	 * For a bone drawn by the affine transform {@code c} (column-major 12 floats: its columns, then the translation, from the
	 * bone's space to the eye's): the inverse's rows (3×3) and the eye in the bone's space, into {@code out[o..o+11]}. False
	 * when the transform is flat (a zero scale): then nothing of that bone may be left out.
	 */
	public static boolean invert(float[] c, int co, float[] out, int o) {
		float a0 = c[co];
		float a1 = c[co + 1];
		float a2 = c[co + 2];
		float b0 = c[co + 3];
		float b1 = c[co + 4];
		float b2 = c[co + 5];
		float g0 = c[co + 6];
		float g1 = c[co + 7];
		float g2 = c[co + 8];
		// Rows of the inverse: (b × g, g × a, a × b) / det.
		float r00 = b1 * g2 - b2 * g1;
		float r01 = b2 * g0 - b0 * g2;
		float r02 = b0 * g1 - b1 * g0;
		float det = a0 * r00 + a1 * r01 + a2 * r02;
		if (!(Math.abs(det) > 1e-12F) || !Float.isFinite(det)) {
			return false;
		}
		float inv = 1F / det;
		float r10 = (g1 * a2 - g2 * a1) * inv;
		float r11 = (g2 * a0 - g0 * a2) * inv;
		float r12 = (g0 * a1 - g1 * a0) * inv;
		float r20 = (a1 * b2 - a2 * b1) * inv;
		float r21 = (a2 * b0 - a0 * b2) * inv;
		float r22 = (a0 * b1 - a1 * b0) * inv;
		r00 *= inv;
		r01 *= inv;
		r02 *= inv;
		float tx = -c[co + 9];
		float ty = -c[co + 10];
		float tz = -c[co + 11];
		out[o] = r00;
		out[o + 1] = r01;
		out[o + 2] = r02;
		out[o + 3] = r10;
		out[o + 4] = r11;
		out[o + 5] = r12;
		out[o + 6] = r20;
		out[o + 7] = r21;
		out[o + 8] = r22;
		out[o + 9] = r00 * tx + r01 * ty + r02 * tz;
		out[o + 10] = r10 * tx + r11 * ty + r12 * tz;
		out[o + 11] = r20 * tx + r21 * ty + r22 * tz;
		// An axis normal's plane normal in the eye's space is that row: its length scales the margin.
		out[o + 12] = r00 * r00 + r01 * r01 + r02 * r02;
		out[o + 13] = r10 * r10 + r11 * r11 + r12 * r12;
		out[o + 14] = r20 * r20 + r21 * r21 + r22 * r22;
		return true;
	}

	/**
	 * Which of solid box {@code q}'s six faces ({@code q..q+5}, {@link CosmeticModel#solid}) to draw, bit i for {@code q + i},
	 * for a bone whose {@link #invert} is {@code inv[o..o+14]}: all six unless the eye is clearly outside the box, then all but
	 * those clearly turned away.
	 */
	public static int faces(CosmeticModel m, int q, float[] inv, int o) {
		float[] quads = m.quads;
		float[] d = m.plane;
		byte[] axis = m.axis;
		if (d == null || axis == null) {
			return 0x3F;
		}
		float ex = inv[o + 9];
		float ey = inv[o + 10];
		float ez = inv[o + 11];
		int keep = 0;
		boolean outside = false;
		for (int i = 0; i < 6; i++) {
			int code = axis[q + i];
			float s;
			float g2;
			if (code != 3) {
				// A face along an axis (most cubes): its normal is ±that axis, its plane normal in the eye's space that row.
				int k = code & 3;
				float e = k == 0 ? ex : k == 1 ? ey : ez;
				s = ((code & 4) != 0 ? -e : e) - d[q + i];
				g2 = inv[o + 12 + k];
			} else {
				int at = (q + i) * CosmeticModel.QUAD;
				float nx = quads[at + 20];
				float ny = quads[at + 21];
				float nz = quads[at + 22];
				// The eye's distance in front of the face's plane, in the bone's units; the plane's normal in the eye's space
				// (the inverse transposed) turns it into blocks.
				s = nx * ex + ny * ey + nz * ez - d[q + i];
				float gx = nx * inv[o] + ny * inv[o + 3] + nz * inv[o + 6];
				float gy = nx * inv[o + 1] + ny * inv[o + 4] + nz * inv[o + 7];
				float gz = nx * inv[o + 2] + ny * inv[o + 5] + nz * inv[o + 8];
				g2 = gx * gx + gy * gy + gz * gz;
			}
			// |s| against MARGIN · |g|, squared (no square root).
			boolean far = s * s > MARGIN * MARGIN * g2;
			if (s > 0 && far) {
				outside = true;
			}
			if (s >= 0 || !far) {
				keep |= 1 << i;
			}
		}
		return outside ? keep : 0x3F;
	}
}
