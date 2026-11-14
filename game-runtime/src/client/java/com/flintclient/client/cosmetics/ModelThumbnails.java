package com.flintclient.client.cosmetics;

/**
 * A model's turntable for the Cosmetics window's tiles: the model in its rest pose (idle at 0 s), drawn from the front and a
 * little above at {@link #ANGLES} angles around it, each {@code size × size} pixels, into one ARGB atlas ({@link #ANGLES}
 * cells in a {@link #COLUMNS}-wide grid). Made once per model on the loader thread with a small software rasterizer (depth
 * buffer, nearest texture sampling of frame 0, cut-out alpha, soft light from the front), so a tile turns by showing another
 * cell: drawing allocates and renders nothing. Every angle has the same scale and centre, so the model doesn't jump while it
 * turns. Pure Java.
 */
public final class ModelThumbnails {
	public static final int ANGLES = 16;
	public static final int COLUMNS = 4;
	/** The view's tilt from above, in radians. */
	static final float PITCH = (float) Math.toRadians(18);

	private ModelThumbnails() {
	}

	/** The atlas's width (and height) for cells of {@code size} pixels. */
	public static int atlasSize(int size) {
		return size * COLUMNS;
	}

	/** The cell for {@code yawDegrees} (0: the front). */
	public static int cell(float yawDegrees) {
		float a = yawDegrees % 360;
		if (a < 0) {
			a += 360;
		}
		return Math.round(a / (360F / ANGLES)) % ANGLES;
	}

	/**
	 * The atlas for {@code model} posed as {@code pose} left it, textured from {@code argb} ({@code texW × texH}, frame 0 of
	 * {@code frames} at the top), cells of {@code size} pixels.
	 */
	public static int[] render(CosmeticModel model, ModelPose pose, int[] argb, int texW, int texH, int frames, int size) {
		int atlas = atlasSize(size);
		int[] out = new int[atlas * atlas];
		float[] depth = new float[size * size];
		// Every vertex posed once (anchor spaces put side by side as on a player: their parts' rest pivots).
		int n = model.quadCount * 4;
		float[] px = new float[n];
		float[] py = new float[n];
		float[] pz = new float[n];
		float[] shade = new float[model.quadCount];
		float cx = 0;
		float cy = 0;
		float cz = 0;
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int b = 0; b < model.bones; b++) {
			float[] ap = CosmeticModel.ANCHOR_PIVOTS[model.anchor[b]];
			for (int q = model.quadStart[b]; q < model.quadStart[b + 1]; q++) {
				int at = q * CosmeticModel.QUAD;
				for (int i = 0; i < 4; i++) {
					float[] p = pose.transform(b, model.quads[at + i * 5], model.quads[at + i * 5 + 1], model.quads[at + i * 5 + 2]);
					int v = q * 4 + i;
					px[v] = p[0] + ap[0] / 16F;
					py[v] = p[1] + ap[1] / 16F;
					pz[v] = p[2] + ap[2] / 16F;
				}
			}
		}
		if (n == 0) {
			return out;
		}
		float minX = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE;
		float maxZ = -Float.MAX_VALUE;
		for (int v = 0; v < n; v++) {
			minX = Math.min(minX, px[v]);
			maxX = Math.max(maxX, px[v]);
			minY = Math.min(minY, py[v]);
			maxY = Math.max(maxY, py[v]);
			minZ = Math.min(minZ, pz[v]);
			maxZ = Math.max(maxZ, pz[v]);
		}
		cx = (minX + maxX) / 2;
		cy = (minY + maxY) / 2;
		cz = (minZ + maxZ) / 2;
		float radius = 0;
		for (int v = 0; v < n; v++) {
			float dx = px[v] - cx;
			float dz = pz[v] - cz;
			radius = Math.max(radius, (float) Math.sqrt(dx * dx + dz * dz));
		}
		float half = Math.max(radius, (maxY - minY) / 2 * (float) Math.cos(PITCH) + radius * (float) Math.sin(PITCH));
		float scale = half <= 0 ? 1 : size * 0.45F / half;
		float cp = (float) Math.cos(PITCH);
		float sp = (float) Math.sin(PITCH);
		float[] sx = new float[n];
		float[] sy = new float[n];
		float[] sd = new float[n];
		float frameV = 1F / Math.max(1, frames);
		for (int a = 0; a < ANGLES; a++) {
			float yaw = (float) (a * 2 * Math.PI / ANGLES);
			float cyaw = (float) Math.cos(yaw);
			float syaw = (float) Math.sin(yaw);
			for (int v = 0; v < n; v++) {
				float x = px[v] - cx;
				float y = py[v] - cy;
				float z = pz[v] - cz;
				// Turn about the vertical axis, then tilt so the camera looks down a little (camera at -z).
				float rx = cyaw * x + syaw * z;
				float rz = -syaw * x + cyaw * z;
				float ry = cp * y + sp * rz;
				float rd = -sp * y + cp * rz;
				sx[v] = size / 2F + rx * scale;
				sy[v] = size / 2F + ry * scale;
				sd[v] = rd;
			}
			for (int q = 0; q < model.quadCount; q++) {
				int at = q * CosmeticModel.QUAD;
				// Light from the front-left, above.
				float nx = model.quads[at + 20];
				float ny = model.quads[at + 21];
				float nz = model.quads[at + 22];
				float rnx = cyaw * nx + syaw * nz;
				float rnz = -syaw * nx + cyaw * nz;
				float rny = cp * ny + sp * rnz;
				float rnd = -sp * ny + cp * rnz;
				float light = Math.abs(-0.3F * rnx - 0.5F * rny - 0.8F * rnd);
				shade[q] = 0.55F + 0.45F * Math.min(1, light);
			}
			java.util.Arrays.fill(depth, Float.MAX_VALUE);
			int ox = (a % COLUMNS) * size;
			int oy = (a / COLUMNS) * size;
			for (int q = 0; q < model.quadCount; q++) {
				int v0 = q * 4;
				int at = q * CosmeticModel.QUAD;
				triangle(model, at, 0, 1, 2, v0, sx, sy, sd, shade[q], argb, texW, texH, frameV, size, depth, out, atlas, ox, oy);
				triangle(model, at, 0, 2, 3, v0, sx, sy, sd, shade[q], argb, texW, texH, frameV, size, depth, out, atlas, ox, oy);
			}
		}
		return out;
	}

	private static void triangle(CosmeticModel m, int at, int i0, int i1, int i2, int v0, float[] sx, float[] sy, float[] sd, float shade,
		int[] tex, int texW, int texH, float frameV, int size, float[] depth, int[] out, int atlas, int ox, int oy) {
		float x0 = sx[v0 + i0];
		float y0 = sy[v0 + i0];
		float x1 = sx[v0 + i1];
		float y1 = sy[v0 + i1];
		float x2 = sx[v0 + i2];
		float y2 = sy[v0 + i2];
		float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-6F) {
			return;
		}
		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		float u0 = m.quads[at + i0 * 5 + 3];
		float t0 = m.quads[at + i0 * 5 + 4] * frameV;
		float u1 = m.quads[at + i1 * 5 + 3];
		float t1 = m.quads[at + i1 * 5 + 4] * frameV;
		float u2 = m.quads[at + i2 * 5 + 3];
		float t2 = m.quads[at + i2 * 5 + 4] * frameV;
		float d0 = sd[v0 + i0];
		float d1 = sd[v0 + i1];
		float d2 = sd[v0 + i2];
		for (int y = minY; y <= maxY; y++) {
			float fy = y + 0.5F;
			for (int x = minX; x <= maxX; x++) {
				float fx = x + 0.5F;
				float w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) / area;
				float w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) / area;
				float w2 = 1 - w0 - w1;
				if (w0 < -1e-4F || w1 < -1e-4F || w2 < -1e-4F) {
					continue;
				}
				float d = w0 * d0 + w1 * d1 + w2 * d2;
				int cell = y * size + x;
				if (d >= depth[cell]) {
					continue;
				}
				float u = w0 * u0 + w1 * u1 + w2 * u2;
				float t = w0 * t0 + w1 * t1 + w2 * t2;
				int tx = Math.min(texW - 1, Math.max(0, (int) (u * texW)));
				int ty = Math.min(texH - 1, Math.max(0, (int) (t * texH)));
				int c = tex[ty * texW + tx];
				if ((c >>> 24) < 26) {
					continue;
				}
				depth[cell] = d;
				int r = (int) (((c >> 16) & 0xFF) * shade);
				int g = (int) (((c >> 8) & 0xFF) * shade);
				int b = (int) ((c & 0xFF) * shade);
				out[(oy + y) * atlas + ox + x] = 0xFF000000 | r << 16 | g << 8 | b;
			}
		}
	}
}
