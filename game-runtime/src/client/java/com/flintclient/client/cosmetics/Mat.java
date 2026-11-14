package com.flintclient.client.cosmetics;

/**
 * The little matrix arithmetic posing a model needs, on plain float arrays (no allocation in the per-frame paths). An affine
 * transform is 12 floats, column-major: the 3×3 part ({@code m00 m10 m20 m01 m11 m21 m02 m12 m22}), then the translation.
 */
final class Mat {
	private Mat() {
	}

	/** Rotation by {@code z}, then {@code y}, then {@code x} (radians) as Minecraft's parts turn: Rz·Ry·Rx, 9 floats. */
	static float[] rotationZYX(float z, float y, float x) {
		float[] m = new float[9];
		rotationZYX(z, y, x, m, 0);
		return m;
	}

	/** Rz·Ry·Rx into {@code out[at..at+8]}, column-major. */
	static void rotationZYX(float z, float y, float x, float[] out, int at) {
		float cz = (float) Math.cos(z);
		float sz = (float) Math.sin(z);
		float cy = (float) Math.cos(y);
		float sy = (float) Math.sin(y);
		float cx = (float) Math.cos(x);
		float sx = (float) Math.sin(x);
		// Rz·Ry·Rx (JOML's rotationZYX).
		out[at] = cz * cy;
		out[at + 1] = sz * cy;
		out[at + 2] = -sy;
		out[at + 3] = cz * sy * sx - sz * cx;
		out[at + 4] = sz * sy * sx + cz * cx;
		out[at + 5] = cy * sx;
		out[at + 6] = cz * sy * cx + sz * sx;
		out[at + 7] = sz * sy * cx - cz * sx;
		out[at + 8] = cy * cx;
	}

	/** {@code out[o] = a[ao] · b[bo]} for affine transforms (12 floats each); {@code out} may not overlap either. */
	static void mul(float[] a, int ao, float[] b, int bo, float[] out, int o) {
		for (int c = 0; c < 3; c++) {
			float b0 = b[bo + c * 3];
			float b1 = b[bo + c * 3 + 1];
			float b2 = b[bo + c * 3 + 2];
			out[o + c * 3] = a[ao] * b0 + a[ao + 3] * b1 + a[ao + 6] * b2;
			out[o + c * 3 + 1] = a[ao + 1] * b0 + a[ao + 4] * b1 + a[ao + 7] * b2;
			out[o + c * 3 + 2] = a[ao + 2] * b0 + a[ao + 5] * b1 + a[ao + 8] * b2;
		}
		float tx = b[bo + 9];
		float ty = b[bo + 10];
		float tz = b[bo + 11];
		out[o + 9] = a[ao] * tx + a[ao + 3] * ty + a[ao + 6] * tz + a[ao + 9];
		out[o + 10] = a[ao + 1] * tx + a[ao + 4] * ty + a[ao + 7] * tz + a[ao + 10];
		out[o + 11] = a[ao + 2] * tx + a[ao + 5] * ty + a[ao + 8] * tz + a[ao + 11];
	}
}
