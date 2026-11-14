package com.flintclient.client.cosmetics;

import com.flintclient.client.cosmetics.CosmeticGeometry.Bone;
import com.flintclient.client.cosmetics.CosmeticGeometry.Cube;
import java.util.Locale;

/**
 * A {@link CosmeticGeometry} baked once into flat arrays for drawing: every face a quad of four vertices (position in blocks,
 * texture coordinates 0..1 of one frame, the face's normal), grouped by bone; every bone its pivot, rest rotation and the
 * player part it hangs from ({@link #anchor}).
 *
 * <p>Space: Minecraft's player model space, where the player's own parts are posed (y down, 16 pixels to a block, the
 * player's right at -x, the back at +z), each bone relative to its anchor part's rest pivot so that drawing it after that
 * part's own transform puts it on the posed part. From Bedrock's (y up from the feet) that is {@code (x, 24 - y, z)};
 * Bedrock's rotations keep their numbers (GeckoLib's reading of Blockbench's convention, turned by Minecraft's model flip),
 * applied Z, then Y, then X as Minecraft's own parts are. Box UVs are laid out as Minecraft's cubes are (the same layout as
 * Bedrock's; Bedrock floors fractional sizes); per-face UVs map {@code uv} to the face's top left as Blockbench writes them.
 *
 * <p>A root bone named {@code anchor_body}, {@code anchor_head}, {@code anchor_right_arm}/{@code anchor_rightarm} (and the left
 * arm and legs alike) hangs its bones from that part; others from the item's own anchor. {@code mirror}: drawn on the other
 * side (a pet on the left shoulder), x turned around. Pure Java; immutable after baking.
 */
public final class CosmeticModel {
	public static final int BODY = 0;
	public static final int HEAD = 1;
	public static final int RIGHT_ARM = 2;
	public static final int LEFT_ARM = 3;
	public static final int RIGHT_LEG = 4;
	public static final int LEFT_LEG = 5;
	public static final int ANCHORS = 6;
	/** The parts' rest pivots in model space (pixels): Minecraft's player model, as Bedrock's player geometry. */
	static final float[][] ANCHOR_PIVOTS = {{0, 0, 0}, {0, 0, 0}, {-5, 2, 0}, {5, 2, 0}, {-1.9F, 12, 0}, {1.9F, 12, 0}};
	/** Floats per quad: 4 × (x, y, z, u, v), then the normal. */
	public static final int QUAD = 23;
	/** How far a thin plate's back face sits behind its front (pixels). */
	static final float PLATE = 0.02F;

	public final int textureWidth;
	public final int textureHeight;
	public final int bones;
	public final String[] names;
	/** Each bone's parent (before it), or -1 where its chain starts (a root, or a bone that starts another anchor). */
	public final int[] parent;
	public final int[] anchor;
	/** Pivots in blocks, three per bone. */
	public final float[] pivot;
	/** Rest rotations in radians, three per bone (x, y, z). */
	public final float[] rotation;
	/** Quads of bone {@code b}: {@code quadStart[b]} to {@code quadStart[b + 1]}. */
	public final int[] quadStart;
	public final float[] quads;
	/** Each quad's area in square pixels (16 to a block): far away, the smallest are left out ({@code CosmeticsLayer}'s detail). */
	public final float[] area;
	/**
	 * Others' detail: faces smaller than this (square pixels) are left out of another player's copy, so at most
	 * {@link #LOD_QUADS} of the biggest faces draw (the silhouette stays; small detail goes). 0 when the model is that small.
	 */
	public final float lodArea;
	/** The most faces another player's copy of one item draws. */
	public static final int LOD_QUADS = 256;
	public final int quadCount;
	/** Which anchors any quad hangs from (bit per anchor). */
	public final int anchorsUsed;
	public final boolean mirror;
	/** Bounds of every vertex in blocks (min x, y, z, max x, y, z), for culling and the preview's framing. */
	public final float[] bounds;
	/**
	 * Solid boxes ({@link #markSolidBoxes}): 1 at the first of six quads in a row that close a convex box whose every face is
	 * fully opaque, else 0; null when there are none (or the texture is blended). Seen from outside such a box, its faces
	 * turned away are behind its own faces turned toward the camera on every pixel, so a drawer may leave them out
	 * ({@link #side}) with nothing on screen changed. Set once on the loader thread, before the model is handed over.
	 */
	public byte @org.jspecify.annotations.Nullable [] solid;
	/** Per quad, its plane's distance from the bone's origin along its normal (blocks): the camera's side of the face. */
	public float @org.jspecify.annotations.Nullable [] plane;
	/** Per quad of a solid box: the axis its normal lies along (0 x, 1 y, 2 z) and +1/-1 its way; 3 when it lies along none. */
	public byte @org.jspecify.annotations.Nullable [] axis;

	private CosmeticModel(CosmeticGeometry g, int defaultAnchor, boolean mirror) {
		this.mirror = mirror;
		textureWidth = g.textureWidth();
		textureHeight = g.textureHeight();
		bones = g.bones().size();
		names = new String[bones];
		parent = new int[bones];
		anchor = new int[bones];
		pivot = new float[bones * 3];
		rotation = new float[bones * 3];
		quadStart = new int[bones + 1];
		int faces = 0;
		for (Bone b : g.bones()) {
			faces += b.cubes().size() * 6;
		}
		float[] out = new float[faces * QUAD];
		int q = 0;
		int used = 0;
		float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = 0; i < bones; i++) {
			Bone b = g.bones().get(i);
			names[i] = b.name();
			int own = anchorNamed(b.name());
			int p = b.parent();
			anchor[i] = own >= 0 ? own : p >= 0 ? anchor[p] : defaultAnchor;
			// A bone that starts another anchor's chain doesn't take its parent's transform (another part's space).
			parent[i] = own >= 0 ? -1 : p;
			float[] ap = ANCHOR_PIVOTS[mirrored(anchor[i], mirror)];
			float s = mirror ? -1 : 1;
			pivot[i * 3] = (s * b.pivot()[0] - ap[0]) / 16F;
			pivot[i * 3 + 1] = (24 - b.pivot()[1] - ap[1]) / 16F;
			pivot[i * 3 + 2] = (b.pivot()[2] - ap[2]) / 16F;
			rotation[i * 3] = (float) Math.toRadians(b.rotation()[0]);
			rotation[i * 3 + 1] = (float) Math.toRadians(b.rotation()[1]) * s;
			rotation[i * 3 + 2] = (float) Math.toRadians(b.rotation()[2]) * s;
			quadStart[i] = q;
			for (Cube c : b.cubes()) {
				q = cube(c, ap, out, q, box);
			}
			if (q > quadStart[i]) {
				used |= 1 << mirrored(anchor[i], mirror);
			}
		}
		quadStart[bones] = q;
		quadCount = q;
		quads = java.util.Arrays.copyOf(out, q * QUAD);
		area = areas(quads, q);
		lodArea = lodArea(area, q);
		anchorsUsed = used;
		bounds = q == 0 ? new float[6] : box;
		for (int i = 0; i < bones; i++) {
			anchor[i] = mirrored(anchor[i], mirror);
		}
	}

	/** {@code geometry} baked; {@code defaultAnchor}: where bones under no anchor bone hang. */
	public static CosmeticModel bake(CosmeticGeometry geometry, int defaultAnchor, boolean mirror) {
		return new CosmeticModel(geometry, defaultAnchor, mirror);
	}

	/** A copy with other quads (the bones as they are). */
	private CosmeticModel(CosmeticModel m, int[] quadStart, float[] quads, int quadCount, int anchorsUsed) {
		textureWidth = m.textureWidth;
		textureHeight = m.textureHeight;
		bones = m.bones;
		names = m.names;
		parent = m.parent;
		anchor = m.anchor;
		pivot = m.pivot;
		rotation = m.rotation;
		mirror = m.mirror;
		bounds = m.bounds;
		this.quadStart = quadStart;
		this.quads = quads;
		this.area = areas(quads, quadCount);
		this.lodArea = lodArea(area, quadCount);
		this.quadCount = quadCount;
		this.anchorsUsed = anchorsUsed;
	}

	/**
	 * This model without the faces whose texture is fully transparent in every frame ({@code argb}: {@code w × h}, {@code frames}
	 * stacked from the top): Blockbench models often keep such faces, and they cost as much to draw as visible ones. This model
	 * itself when every face shows something.
	 */
	/**
	 * This model without the walls between touching cubes of one bone: a face and the opposite face of the cube against it,
	 * on the same rectangle. They are kept unless drawing the model without them changes no pixel ({@link ModelThumbnails},
	 * 16 angles, the texture's first frame): the result is exactly what was drawn, with fewer faces. This model itself when
	 * there are none, or when dropping them would show.
	 */
	public CosmeticModel withoutInnerWalls(int[] argb, int w, int h, int frames) {
		boolean[] drop = new boolean[quadCount];
		int dropped = 0;
		for (int b = 0; b < bones; b++) {
			for (int i = quadStart[b]; i < quadStart[b + 1]; i++) {
				if (drop[i]) {
					continue;
				}
				for (int j = i + 1; j < quadStart[b + 1]; j++) {
					if (!drop[j] && sameWall(i, j)) {
						drop[i] = true;
						drop[j] = true;
						dropped += 2;
						break;
					}
				}
			}
		}
		if (dropped == 0) {
			return this;
		}
		CosmeticModel lean = without(drop, quadCount - dropped);
		int size = 48;
		int[] before = ModelThumbnails.render(this, new ModelPose(this, null), argb, w, h, frames, size);
		int[] after = ModelThumbnails.render(lean, new ModelPose(lean, null), argb, w, h, frames, size);
		return java.util.Arrays.equals(before, after) ? lean : this;
	}

	/** Whether quads {@code i} and {@code j} face each other on one rectangle (normals opposite, the same four corners). */
	private boolean sameWall(int i, int j) {
		int a = i * QUAD;
		int b = j * QUAD;
		float dot = quads[a + 20] * quads[b + 20] + quads[a + 21] * quads[b + 21] + quads[a + 22] * quads[b + 22];
		if (dot > -0.999F) {
			return false;
		}
		for (int k = 0; k < 4; k++) {
			boolean found = false;
			for (int l = 0; l < 4 && !found; l++) {
				found = Math.abs(quads[a + k * 5] - quads[b + l * 5]) < 1e-5F && Math.abs(quads[a + k * 5 + 1] - quads[b + l * 5 + 1]) < 1e-5F
					&& Math.abs(quads[a + k * 5 + 2] - quads[b + l * 5 + 2]) < 1e-5F;
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	/** A copy without the quads {@code drop} marks ({@code kept} remain). */
	private CosmeticModel without(boolean[] drop, int kept) {
		int[] start = new int[bones + 1];
		float[] out = new float[kept * QUAD];
		int n = 0;
		int used = 0;
		for (int b = 0; b < bones; b++) {
			start[b] = n;
			for (int q = quadStart[b]; q < quadStart[b + 1]; q++) {
				if (!drop[q]) {
					System.arraycopy(quads, q * QUAD, out, n * QUAD, QUAD);
					n++;
				}
			}
			if (n > start[b]) {
				used |= 1 << anchor[b];
			}
		}
		start[bones] = n;
		return new CosmeticModel(this, start, out, n, used);
	}

	public CosmeticModel visibleOnly(int[] argb, int w, int h, int frames) {
		boolean[] keep = new boolean[quadCount];
		int kept = 0;
		for (int q = 0; q < quadCount; q++) {
			keep[q] = shows(q, argb, w, h, frames);
			if (keep[q]) {
				kept++;
			}
		}
		if (kept == quadCount) {
			return this;
		}
		int[] start = new int[bones + 1];
		float[] out = new float[kept * QUAD];
		int n = 0;
		int used = 0;
		for (int b = 0; b < bones; b++) {
			start[b] = n;
			for (int q = quadStart[b]; q < quadStart[b + 1]; q++) {
				if (keep[q]) {
					System.arraycopy(quads, q * QUAD, out, n * QUAD, QUAD);
					n++;
				}
			}
			if (n > start[b]) {
				used |= 1 << anchor[b];
			}
		}
		start[bones] = n;
		return new CosmeticModel(this, start, out, n, used);
	}

	/** A pixel at least this opaque is drawn by a cut-out texture (Minecraft cuts below 0.1; kept well clear of it). */
	static final int OPAQUE_ALPHA = 64;

	/**
	 * Finds the solid boxes ({@link #solid}) of a cut-out texture ({@code argb}: {@code w × h}, {@code frames} stacked): six
	 * quads in a row of one bone whose corners are a box's eight (each corner on three faces), every other corner on or behind
	 * each face's plane (convex, normals outward), and every pixel of every face opaque in every frame. Answers how many quads
	 * are in such boxes. Loader thread.
	 */
	public int markSolidBoxes(int[] argb, int w, int h, int frames) {
		return markSolidBoxes(argb, w, h, frames, OPAQUE_ALPHA);
	}

	/** As {@link #markSolidBoxes(int[], int, int, int)}, a pixel counting as opaque from {@code least} alpha (255 for a blended texture). */
	public int markSolidBoxes(int[] argb, int w, int h, int frames, int least) {
		byte[] marks = new byte[quadCount];
		float[] d = new float[quadCount];
		for (int q = 0; q < quadCount; q++) {
			int o = q * QUAD;
			d[q] = quads[o + 20] * quads[o] + quads[o + 21] * quads[o + 1] + quads[o + 22] * quads[o + 2];
		}
		int found = 0;
		for (int b = 0; b < bones; b++) {
			int end = quadStart[b + 1];
			for (int q = quadStart[b]; q + 6 <= end; q++) {
				if (box(q, d) && opaque(q, 6, argb, w, h, frames, least)) {
					marks[q] = 1;
					found += 6;
					q += 5;
				}
			}
		}
		if (found > 0) {
			plane = d;
			axis = axes();
			solid = marks;
		}
		return found;
	}

	/** Each quad's {@link #axis}: bits 0-1 the axis (3: none), bit 2 set when the normal points the negative way. */
	private byte[] axes() {
		byte[] out = new byte[quadCount];
		for (int q = 0; q < quadCount; q++) {
			int o = q * QUAD;
			byte code = 3;
			for (int k = 0; k < 3; k++) {
				float c = quads[o + 20 + k];
				float rest = quads[o + 20 + (k + 1) % 3] * quads[o + 20 + (k + 1) % 3] + quads[o + 20 + (k + 2) % 3] * quads[o + 20 + (k + 2) % 3];
				if (rest == 0 && (c == 1 || c == -1)) {
					code = (byte) (k | (c < 0 ? 4 : 0));
				}
			}
			out[q] = code;
		}
		return out;
	}

	/** Whether quads {@code q..q+5} close a convex box with outward normals ({@code d}: each quad's plane). */
	private boolean box(int q, float[] d) {
		float[] corner = new float[24];
		int[] uses = new int[8];
		int corners = 0;
		for (int f = q; f < q + 6; f++) {
			for (int i = 0; i < 4; i++) {
				int v = f * QUAD + i * 5;
				int at = -1;
				for (int c = 0; c < corners && at < 0; c++) {
					if (Math.abs(corner[c * 3] - quads[v]) < 1e-5F && Math.abs(corner[c * 3 + 1] - quads[v + 1]) < 1e-5F
						&& Math.abs(corner[c * 3 + 2] - quads[v + 2]) < 1e-5F) {
						at = c;
					}
				}
				if (at < 0) {
					if (corners == 8) {
						return false;
					}
					at = corners++;
					corner[at * 3] = quads[v];
					corner[at * 3 + 1] = quads[v + 1];
					corner[at * 3 + 2] = quads[v + 2];
				}
				uses[at]++;
			}
		}
		if (corners != 8) {
			return false;
		}
		float size = 0;
		for (int c = 0; c < 8; c++) {
			if (uses[c] != 3) {
				return false;
			}
			for (int k = 0; k < 3; k++) {
				size = Math.max(size, Math.abs(corner[c * 3 + k] - corner[k]));
			}
		}
		if (size < 1e-4F) {
			return false;
		}
		float tolerance = size * 1e-4F;
		for (int f = q; f < q + 6; f++) {
			int o = f * QUAD;
			float nx = quads[o + 20];
			float ny = quads[o + 21];
			float nz = quads[o + 22];
			if (nx * nx + ny * ny + nz * nz < 0.25F) {
				return false;
			}
			boolean behind = false;
			for (int c = 0; c < 8; c++) {
				float s = nx * corner[c * 3] + ny * corner[c * 3 + 1] + nz * corner[c * 3 + 2] - d[f];
				if (s > tolerance) {
					// A corner in front of a face: not convex, or the normal points in.
					return false;
				}
				behind |= s < -tolerance;
			}
			if (!behind) {
				// Every corner on the face's plane: flat (a plate), seen from both sides.
				return false;
			}
		}
		return true;
	}

	/** Whether every pixel of quads {@code q..q+n-1} is opaque in every frame (inside the texture). */
	private boolean opaque(int q, int n, int[] argb, int w, int h, int frames, int least) {
		int frameH = h / Math.max(1, frames);
		for (int f = q; f < q + n; f++) {
			float u0 = Float.MAX_VALUE;
			float u1 = -Float.MAX_VALUE;
			float v0 = Float.MAX_VALUE;
			float v1 = -Float.MAX_VALUE;
			for (int i = 0; i < 4; i++) {
				float u = quads[f * QUAD + i * 5 + 3];
				float v = quads[f * QUAD + i * 5 + 4];
				u0 = Math.min(u0, u);
				u1 = Math.max(u1, u);
				v0 = Math.min(v0, v);
				v1 = Math.max(v1, v);
			}
			if (u0 < 0 || v0 < 0 || u1 > 1 || v1 > 1) {
				return false;
			}
			int x0 = (int) Math.floor(u0 * w);
			int x1 = Math.min(w, Math.max(x0 + 1, (int) Math.ceil(u1 * w)));
			int y0 = (int) Math.floor(v0 * frameH);
			int y1 = Math.min(frameH, Math.max(y0 + 1, (int) Math.ceil(v1 * frameH)));
			if (x0 >= w || y0 >= frameH) {
				return false;
			}
			for (int fr = 0; fr < Math.max(1, frames); fr++) {
				for (int y = y0; y < y1; y++) {
					int row = (fr * frameH + y) * w;
					for (int x = x0; x < x1; x++) {
						if ((argb[row + x] >>> 24) < least) {
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	/** Alpha from which a pixel is drawn at all (Minecraft's entity shaders cut below 0.1). */
	static final int DRAWN_ALPHA = 26;

	/**
	 * Per quad of a blended texture: 1 when some pixel of it, in some frame, is partly transparent (drawn at all but not fully
	 * opaque), 0 when each of its pixels is either fully opaque or not drawn ({@link #DRAWN_ALPHA}); null when none is blended.
	 * A quad of 0 looks exactly the same cut out as blended, so it is drawn cut out and only the 1s are sorted and blended
	 * ({@link #markBlended}).
	 */
	public byte @org.jspecify.annotations.Nullable [] blend;
	/** {@link #markBlended} ran (a blended texture): {@link #blend} null then means no quad needs blending. */
	public boolean blendMarked;

	/** Finds the quads of a blended texture that need blending ({@link #blend}); answers how many. Loader thread. */
	public int markBlended(int[] argb, int w, int h, int frames) {
		byte[] marks = new byte[quadCount];
		int frameH = h / Math.max(1, frames);
		int found = 0;
		for (int q = 0; q < quadCount; q++) {
			float u0 = Float.MAX_VALUE;
			float u1 = -Float.MAX_VALUE;
			float v0 = Float.MAX_VALUE;
			float v1 = -Float.MAX_VALUE;
			for (int i = 0; i < 4; i++) {
				float u = quads[q * QUAD + i * 5 + 3];
				float v = quads[q * QUAD + i * 5 + 4];
				u0 = Math.min(u0, u);
				u1 = Math.max(u1, u);
				v0 = Math.min(v0, v);
				v1 = Math.max(v1, v);
			}
			boolean partly = u0 < 0 || v0 < 0 || u1 > 1 || v1 > 1;
			int x0 = Math.max(0, (int) Math.floor(u0 * w));
			int x1 = Math.min(w, Math.max(x0 + 1, (int) Math.ceil(u1 * w)));
			int y0 = Math.max(0, (int) Math.floor(v0 * frameH));
			int y1 = Math.min(frameH, Math.max(y0 + 1, (int) Math.ceil(v1 * frameH)));
			partly |= x0 >= w || y0 >= frameH;
			for (int fr = 0; fr < Math.max(1, frames) && !partly; fr++) {
				for (int y = y0; y < y1 && !partly; y++) {
					int row = (fr * frameH + y) * w;
					for (int x = x0; x < x1; x++) {
						int a = argb[row + x] >>> 24;
						if (a >= DRAWN_ALPHA && a < 255) {
							partly = true;
							break;
						}
					}
				}
			}
			if (partly) {
				marks[q] = 1;
				found++;
			}
		}
		blend = found == 0 ? null : marks;
		blendMarked = true;
		return found;
	}

	/** Whether quad {@code q}'s texture area has any pixel that isn't fully transparent, in any frame. */
	private boolean shows(int q, int[] argb, int w, int h, int frames) {
		float u0 = Float.MAX_VALUE;
		float u1 = -Float.MAX_VALUE;
		float v0 = Float.MAX_VALUE;
		float v1 = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float u = quads[q * QUAD + i * 5 + 3];
			float v = quads[q * QUAD + i * 5 + 4];
			u0 = Math.min(u0, u);
			u1 = Math.max(u1, u);
			v0 = Math.min(v0, v);
			v1 = Math.max(v1, v);
		}
		int x0 = Math.max(0, (int) Math.floor(u0 * w));
		int x1 = Math.min(w, Math.max(x0 + 1, (int) Math.ceil(u1 * w)));
		int frameH = h / Math.max(1, frames);
		int y0 = Math.max(0, (int) Math.floor(v0 * frameH));
		int y1 = Math.min(frameH, Math.max(y0 + 1, (int) Math.ceil(v1 * frameH)));
		if (x0 >= w || y0 >= frameH) {
			// Outside the texture: Minecraft clamps or wraps; keep it rather than guess.
			return true;
		}
		for (int f = 0; f < Math.max(1, frames); f++) {
			for (int y = y0; y < y1; y++) {
				int row = (f * frameH + y) * w;
				for (int x = x0; x < x1; x++) {
					if ((argb[row + x] >>> 24) != 0) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Whether a texture is drawn with blending: more than 1% of its visible pixels are partly transparent (glass, glow). Others
	 * are cut out (a pixel shows or doesn't), which Minecraft draws without sorting faces every frame.
	 */
	public static boolean translucent(int[] argb) {
		int visible = 0;
		int partial = 0;
		for (int c : argb) {
			int a = c >>> 24;
			if (a != 0) {
				visible++;
				if (a > 25 && a < 230) {
					partial++;
				}
			}
		}
		return partial * 100L > visible;
	}

	/** The area of the {@link #LOD_QUADS}-th biggest face, plus a hair (so at most that many are as big), or 0. */
	static float lodArea(float[] area, int count) {
		if (count <= LOD_QUADS) {
			return 0;
		}
		float[] sorted = java.util.Arrays.copyOf(area, count);
		java.util.Arrays.sort(sorted);
		// Faces at least as big as the LOD_QUADS-th biggest; ties above it are dropped too (never more than the cap).
		return Math.nextUp(sorted[count - LOD_QUADS - 1]);
	}

	private static float[] areas(float[] quads, int count) {
		float[] out = new float[count];
		for (int q = 0; q < count; q++) {
			int o = q * QUAD;
			float ax = quads[o + 5] - quads[o];
			float ay = quads[o + 6] - quads[o + 1];
			float az = quads[o + 7] - quads[o + 2];
			float bx = quads[o + 15] - quads[o];
			float by = quads[o + 16] - quads[o + 1];
			float bz = quads[o + 17] - quads[o + 2];
			float cx = ay * bz - az * by;
			float cy = az * bx - ax * bz;
			float cz = ax * by - ay * bx;
			out[q] = (float) Math.sqrt(cx * cx + cy * cy + cz * cz) * 256;
		}
		return out;
	}

	/** The anchor a bone's name names, or -1. */
	static int anchorNamed(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (!n.startsWith("anchor_")) {
			return -1;
		}
		return switch (n.substring(7).replace("_", "")) {
			case "body" -> BODY;
			case "head" -> HEAD;
			case "rightarm" -> RIGHT_ARM;
			case "leftarm" -> LEFT_ARM;
			case "rightleg" -> RIGHT_LEG;
			case "leftleg" -> LEFT_LEG;
			default -> -1;
		};
	}

	/** The anchor on the other side when drawn mirrored (a right-shoulder pet on the left). */
	static int mirrored(int anchor, boolean mirror) {
		if (!mirror) {
			return anchor;
		}
		return switch (anchor) {
			case RIGHT_ARM -> LEFT_ARM;
			case LEFT_ARM -> RIGHT_ARM;
			case RIGHT_LEG -> LEFT_LEG;
			case LEFT_LEG -> RIGHT_LEG;
			default -> anchor;
		};
	}

	/** A cube's faces from quad {@code q} on; answers the next free quad. */
	private int cube(Cube c, float[] ap, float[] out, int q, float[] box) {
		float s = mirror ? -1 : 1;
		float g = c.inflate();
		// Bedrock (y up from the feet) to model space (y down from the neck), relative to the anchor's pivot.
		float x0 = c.origin()[0];
		float x1 = c.origin()[0] + c.size()[0];
		float y0 = 24 - c.origin()[1] - c.size()[1];
		float y1 = 24 - c.origin()[1];
		float z0 = c.origin()[2];
		float z1 = c.origin()[2] + c.size()[2];
		float minX = x0 - g;
		float maxX = x1 + g;
		float minY = y0 - g;
		float maxY = y1 + g;
		float minZ = z0 - g;
		float maxZ = z1 + g;
		boolean boxMirror = c.mirror() && c.boxUv() != null;
		if (boxMirror) {
			float t = maxX;
			maxX = minX;
			minX = t;
		}
		// Minecraft's cube corners (ModelPart.Cube): t at minZ, l at maxZ.
		float[][] v = {
			{minX, minY, minZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {minX, maxY, minZ},
			{minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}};
		// Per face (Bedrock's order: north, east, south, west, up, down): its corners as Minecraft's cube lists them, and normal.
		int[][] corners = {{1, 0, 3, 2}, {5, 1, 2, 6}, {4, 5, 6, 7}, {0, 4, 7, 3}, {5, 4, 0, 1}, {2, 3, 7, 6}};
		float[][] normals = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
		float[][] rects = new float[6][];
		float[] rotations = new float[6];
		if (c.boxUv() != null) {
			float u = c.boxUv()[0];
			float vv = c.boxUv()[1];
			float w = (float) Math.floor(c.size()[0]);
			float h = (float) Math.floor(c.size()[1]);
			float d = (float) Math.floor(c.size()[2]);
			float u1 = u + d;
			float u2 = u + d + w;
			float u22 = u + d + w + w;
			float u3 = u + d + w + d;
			float u4 = u + d + w + d + w;
			float v1 = vv + d;
			float v2 = vv + d + h;
			rects[CosmeticGeometry.NORTH] = new float[] {u1, v1, u2, v2};
			rects[CosmeticGeometry.EAST] = new float[] {u2, v1, u3, v2};
			rects[CosmeticGeometry.SOUTH] = new float[] {u3, v1, u4, v2};
			rects[CosmeticGeometry.WEST] = new float[] {u, v1, u1, v2};
			rects[CosmeticGeometry.UP] = new float[] {u1, vv, u2, v1};
			rects[CosmeticGeometry.DOWN] = new float[] {u2, v1, u22, vv};
		} else if (c.faces() != null) {
			for (int f = 0; f < 6; f++) {
				float[] face = c.faces()[f];
				if (face != null) {
					rects[f] = new float[] {face[0], face[1], face[0] + face[2], face[1] + face[3]};
					rotations[f] = face[4];
				}
			}
		}
		// The cube's own rotation about its pivot (Bedrock numbers, as for bones).
		float[] rot = c.rotation();
		boolean turned = rot != null && (rot[0] != 0 || rot[1] != 0 || rot[2] != 0);
		float[] m = null;
		float px = 0;
		float py = 0;
		float pz = 0;
		if (turned) {
			float[] cp = c.pivot() != null ? c.pivot() : new float[] {0, 0, 0};
			px = cp[0];
			py = 24 - cp[1];
			pz = cp[2];
			m = Mat.rotationZYX((float) Math.toRadians(rot[2]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[0]));
		}
		// A thin plate (no thickness on an axis): both its faces would sit in one plane and fight; as the website draws them,
		// the front one stays and the back one (the face toward +x, +z, or Bedrock's down) moves a hair behind, so each side
		// shows its own art.
		int pushed = -1;
		float[] push = {0, 0, 0};
		if (g == 0) {
			if (c.size()[2] == 0) {
				pushed = CosmeticGeometry.SOUTH;
				push[2] = PLATE;
			} else if (c.size()[0] == 0) {
				pushed = CosmeticGeometry.EAST;
				push[0] = PLATE;
			} else if (c.size()[1] == 0) {
				pushed = CosmeticGeometry.DOWN;
				push[1] = PLATE;
			}
		}
		float tw = textureWidth;
		float th = textureHeight;
		for (int f = 0; f < 6; f++) {
			float[] r = rects[f];
			if (r == null || area(v, corners[f]) < 1e-6F) {
				continue;
			}
			int[] k = corners[f];
			// Face corners 0..3 take the rectangle's (u1, v0), (u0, v0), (u0, v1), (u1, v1), turned by uv_rotation.
			float[][] uv = {{r[2], r[1]}, {r[0], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
			int turn = Math.floorMod(Math.round(rotations[f] / 90F), 4);
			int base = q * QUAD;
			for (int i = 0; i < 4; i++) {
				int corner = boxMirror ? k[3 - i] : k[i];
				float[] p = v[corner];
				float x = p[0];
				float y = p[1];
				float z = p[2];
				if (f == pushed) {
					x += push[0];
					y += push[1];
					z += push[2];
				}
				if (m != null) {
					float dx = x - px;
					float dy = y - py;
					float dz = z - pz;
					x = px + m[0] * dx + m[3] * dy + m[6] * dz;
					y = py + m[1] * dx + m[4] * dy + m[7] * dz;
					z = pz + m[2] * dx + m[5] * dy + m[8] * dz;
				}
				x = (s * x - ap[0]) / 16F;
				y = (y - ap[1]) / 16F;
				z = (z - ap[2]) / 16F;
				int o = base + i * 5;
				out[o] = x;
				out[o + 1] = y;
				out[o + 2] = z;
				float[] t = uv[boxMirror ? 3 - ((i + turn) & 3) : (i + turn) & 3];
				out[o + 3] = t[0] / tw;
				out[o + 4] = t[1] / th;
				box[0] = Math.min(box[0], x);
				box[1] = Math.min(box[1], y);
				box[2] = Math.min(box[2], z);
				box[3] = Math.max(box[3], x);
				box[4] = Math.max(box[4], y);
				box[5] = Math.max(box[5], z);
			}
			float[] n = normals[f];
			float nx = boxMirror && f % 2 == 1 ? -n[0] : n[0];
			float ny = n[1];
			float nz = n[2];
			if (m != null) {
				float ax = m[0] * nx + m[3] * ny + m[6] * nz;
				float ay = m[1] * nx + m[4] * ny + m[7] * nz;
				float az = m[2] * nx + m[5] * ny + m[8] * nz;
				nx = ax;
				ny = ay;
				nz = az;
			}
			out[base + 20] = s * nx;
			out[base + 21] = ny;
			out[base + 22] = nz;
			q++;
		}
		return q;
	}

	/** Twice a face's area, roughly (zero for a face of a flat cube's edge). */
	private static float area(float[][] v, int[] k) {
		float[] a = v[k[0]];
		float[] b = v[k[1]];
		float[] c = v[k[2]];
		float ux = b[0] - a[0];
		float uy = b[1] - a[1];
		float uz = b[2] - a[2];
		float wx = c[0] - a[0];
		float wy = c[1] - a[1];
		float wz = c[2] - a[2];
		float cx = uy * wz - uz * wy;
		float cy = uz * wx - ux * wz;
		float cz = ux * wy - uy * wx;
		return cx * cx + cy * cy + cz * cz;
	}

	/** The index of the bone called {@code name} (as animations name them, case aside), or -1. */
	public int bone(String name) {
		for (int i = 0; i < bones; i++) {
			if (names[i].equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	/** Vertices drawn per frame. */
	public int vertices() {
		return quadCount * 4;
	}
}
