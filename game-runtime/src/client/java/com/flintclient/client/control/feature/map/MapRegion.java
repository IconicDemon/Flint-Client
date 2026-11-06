package com.flintclient.client.control.feature.map;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * What the map knows about one 512 × 512 block region (32 × 32 chunks, the same grid as Minecraft's region files): per
 * block column the colour of what you see from above, its height and its biome; and a 64 × 64 preview (one pixel per 8 × 8
 * blocks) for zoomed-out views.
 *
 * <p>A column's colour packs a kind in its top byte: {@link #UNKNOWN} (never seen), {@link #LAND} or {@link #WATER} (water
 * isn't slope-shaded); the low 24 bits are the colour, unshaded (shading depends on the neighbours and the settings, so it
 * is applied when a texture is built). Every chunk has a revision stamp: textures and previews rebuild only the chunks
 * that changed since they were made, and a rescan that finds the same thing changes nothing.
 *
 * <p>A region is either complete ({@link #full()}: every array in memory) or only its preview (zoomed-out views far from
 * the player, read from the preview part of its file). Version-neutral; client thread only, except copies handed to the
 * file thread ({@link #snapshot()}).
 */
public final class MapRegion {
	public static final int BITS = 9;
	public static final int SIZE = 1 << BITS;
	public static final int AREA = SIZE * SIZE;
	public static final int CHUNKS = SIZE / 16;
	/** Preview pixels per side, and blocks per preview pixel. */
	public static final int PREVIEW = 64;
	public static final int PREVIEW_STEP = SIZE / PREVIEW;

	public static final int UNKNOWN = 0;
	public static final int LAND = 0xFF;
	public static final int WATER = 0xFE;
	/** A glowing block (lava, glowstone): shaded as Xaero shades light sources. */
	public static final int GLOW = 0xFD;
	/** Nothing down to the bottom (the End's void): flat. */
	public static final int VOID = 0xFC;

	public final int x;
	public final int z;
	private int @Nullable [] color;
	private short @Nullable [] height;
	private byte @Nullable [] biome;
	final int[] previewColor = new int[PREVIEW * PREVIEW];
	final short[] previewHeight = new short[PREVIEW * PREVIEW];
	/** Chunks with data (1024 bits). */
	final long[] known = new long[CHUNKS * CHUNKS / 64];
	/** Chunks seen this session: a file read later never overwrites them. */
	final long[] fresh = new long[CHUNKS * CHUNKS / 64];
	/** Per chunk: the revision that last changed it. */
	final int[] chunkRevision = new int[CHUNKS * CHUNKS];
	private int revision;
	private int previewRevision;
	int savedRevision;
	/** The client tick it was last drawn or written. */
	long lastUse;
	/** Waiting for its file (full or preview). */
	boolean loading;
	boolean fullRequested;

	public MapRegion(int x, int z, boolean full) {
		this.x = x;
		this.z = z;
		if (full) {
			allocate();
		}
	}

	public static long key(int rx, int rz) {
		return (long) rx << 32 | rz & 0xFFFFFFFFL;
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}

	/** Whether every column's data is in memory (not just the preview). */
	public boolean full() {
		return color != null;
	}

	void allocate() {
		if (color == null) {
			color = new int[AREA];
			height = new short[AREA];
			biome = new byte[AREA];
		}
	}

	/** Drops the full data, keeping the preview (memory pressure; the region was saved first). */
	void shrink() {
		color = null;
		height = null;
		biome = null;
	}

	/** Grows with every change; textures compare it with their own. */
	public int revision() {
		return revision;
	}

	/** The revision that last changed chunk ({@code cx}, {@code cz}) of this region (0..31 each). */
	public int chunkRevision(int cx, int cz) {
		return chunkRevision[cz * CHUNKS + cx];
	}

	public boolean known(int cx, int cz) {
		int i = cz * CHUNKS + cx;
		return (known[i >> 6] >>> (i & 63) & 1) != 0;
	}

	/** Whether any chunk has data. */
	public boolean empty() {
		for (long bits : known) {
			if (bits != 0) {
				return false;
			}
		}
		return true;
	}

	public boolean unsaved() {
		return revision != savedRevision;
	}

	/** The packed colour of column ({@code lx}, {@code lz}) (0..511 each); {@link #UNKNOWN} when not full or never seen. */
	public int color(int lx, int lz) {
		int[] c = color;
		return c == null ? 0 : c[lz << BITS | lx];
	}

	public int height(int lx, int lz) {
		short[] h = height;
		return h == null ? 0 : h[lz << BITS | lx];
	}

	/** The column's biome as its palette index + 1 (0: unknown). */
	public int biome(int lx, int lz) {
		byte[] b = biome;
		return b == null ? 0 : b[lz << BITS | lx] & 0xFF;
	}

	public int previewColor(int px, int pz) {
		return previewColor[pz * PREVIEW + px];
	}

	public int previewHeight(int px, int pz) {
		return previewHeight[pz * PREVIEW + px];
	}

	int[] colors() {
		return color;
	}

	short[] heights() {
		return height;
	}

	byte[] biomes() {
		return biome;
	}

	// ---------- writing a chunk ----------

	/**
	 * Writes one chunk's 16 × 16 columns ({@code colors}, {@code heights}, {@code biomes} in z-major order) at chunk
	 * ({@code cx}, {@code cz}) of this region; returns whether anything changed. The region must be full.
	 */
	public boolean writeChunk(int cx, int cz, int[] colors, short[] heights, byte[] biomes) {
		allocate();
		int[] c = color;
		short[] h = height;
		byte[] b = biome;
		boolean changed = false;
		int base = (cz << 4) * SIZE + (cx << 4);
		for (int lz = 0; lz < 16; lz++) {
			int row = base + lz * SIZE;
			int src = lz << 4;
			for (int lx = 0; lx < 16; lx++) {
				int at = row + lx;
				int s = src + lx;
				if (c[at] != colors[s] || h[at] != heights[s] || b[at] != biomes[s]) {
					c[at] = colors[s];
					h[at] = heights[s];
					b[at] = biomes[s];
					changed = true;
				}
			}
		}
		int i = cz * CHUNKS + cx;
		fresh[i >> 6] |= 1L << (i & 63);
		boolean wasKnown = (known[i >> 6] >>> (i & 63) & 1) != 0;
		known[i >> 6] |= 1L << (i & 63);
		if (changed || !wasKnown) {
			chunkRevision[i] = ++revision;
			return true;
		}
		return false;
	}

	/** Forgets everything (a cave layer that moved). */
	void clear() {
		if (color != null) {
			Arrays.fill(color, 0);
			Arrays.fill(height, (short) 0);
			Arrays.fill(biome, (byte) 0);
		}
		Arrays.fill(previewColor, 0);
		Arrays.fill(previewHeight, (short) 0);
		Arrays.fill(known, 0);
		Arrays.fill(fresh, 0);
		revision++;
		Arrays.fill(chunkRevision, revision);
	}

	// ---------- the preview ----------

	/** Brings the preview up to date with the full data (the chunks changed since it was last made). */
	public void updatePreview() {
		int[] c = color;
		if (c == null || previewRevision == revision) {
			return;
		}
		short[] h = height;
		int since = previewRevision;
		for (int cz = 0; cz < CHUNKS; cz++) {
			for (int cx = 0; cx < CHUNKS; cx++) {
				if (chunkRevision[cz * CHUNKS + cx] <= since) {
					continue;
				}
				// A chunk is 2 × 2 preview pixels.
				for (int pz = cz * 2; pz < cz * 2 + 2; pz++) {
					for (int px = cx * 2; px < cx * 2 + 2; px++) {
						previewPixel(c, h, px, pz);
					}
				}
			}
		}
		previewRevision = revision;
	}

	private void previewPixel(int[] c, short[] h, int px, int pz) {
		int r = 0;
		int g = 0;
		int b = 0;
		int n = 0;
		int water = 0;
		int empty = 0;
		int heights = 0;
		for (int z0 = pz * PREVIEW_STEP; z0 < pz * PREVIEW_STEP + PREVIEW_STEP; z0++) {
			for (int x0 = px * PREVIEW_STEP; x0 < px * PREVIEW_STEP + PREVIEW_STEP; x0++) {
				int v = c[z0 << BITS | x0];
				if (v >>> 24 == UNKNOWN) {
					continue;
				}
				r += v >> 16 & 255;
				g += v >> 8 & 255;
				b += v & 255;
				heights += h[z0 << BITS | x0];
				water += v >>> 24 == WATER ? 1 : 0;
				empty += v >>> 24 == VOID ? 1 : 0;
				n++;
			}
		}
		int at = pz * PREVIEW + px;
		if (n == 0) {
			previewColor[at] = 0;
			previewHeight[at] = 0;
			return;
		}
		int kind = water * 2 > n ? WATER : empty * 2 > n ? VOID : LAND;
		previewColor[at] = kind << 24 | (r / n) << 16 | (g / n) << 8 | b / n;
		previewHeight[at] = (short) Math.round(heights / (float) n);
	}

	// ---------- files ----------

	/** A copy of what the file holds, for the file thread. The preview is brought up to date first. */
	public Snapshot snapshot() {
		updatePreview();
		savedRevision = revision;
		int[] c = color;
		return new Snapshot(x, z, known.clone(), previewColor.clone(), previewHeight.clone(), c == null ? null : c.clone(),
			c == null ? null : height.clone(), c == null ? null : biome.clone());
	}

	/** Region data as read from or written to a file. The full arrays are null when only the preview was read. */
	public record Snapshot(int x, int z, long[] known, int[] previewColor, short[] previewHeight, int @Nullable [] color, short @Nullable [] height,
		byte @Nullable [] biome) {
	}

	/**
	 * Takes in what a file held: every chunk not seen this session (seen chunks are newer). A preview-only read fills only
	 * the preview of a region that isn't full.
	 */
	void merge(Snapshot read) {
		if (read.color() != null) {
			allocate();
			int[] c = color;
			short[] h = height;
			byte[] b = biome;
			for (int cz = 0; cz < CHUNKS; cz++) {
				for (int cx = 0; cx < CHUNKS; cx++) {
					int i = cz * CHUNKS + cx;
					boolean seen = (fresh[i >> 6] >>> (i & 63) & 1) != 0;
					boolean has = (read.known()[i >> 6] >>> (i & 63) & 1) != 0;
					if (seen || !has) {
						continue;
					}
					int base = (cz << 4) * SIZE + (cx << 4);
					for (int lz = 0; lz < 16; lz++) {
						int row = base + lz * SIZE;
						System.arraycopy(read.color(), row, c, row, 16);
						System.arraycopy(read.height(), row, h, row, 16);
						System.arraycopy(read.biome(), row, b, row, 16);
					}
					known[i >> 6] |= 1L << (i & 63);
					chunkRevision[i] = revision + 1;
				}
			}
			revision++;
			// The file's own state counts as saved, unless this session added something.
			boolean added = false;
			for (long bits : fresh) {
				added |= bits != 0;
			}
			if (!added) {
				savedRevision = revision;
			}
			previewRevision = 0;
			updatePreview();
			return;
		}
		if (color == null) {
			System.arraycopy(read.previewColor(), 0, previewColor, 0, previewColor.length);
			System.arraycopy(read.previewHeight(), 0, previewHeight, 0, previewHeight.length);
			for (int i = 0; i < known.length; i++) {
				known[i] |= read.known()[i];
			}
			revision++;
			Arrays.fill(chunkRevision, revision);
			previewRevision = revision;
			savedRevision = revision;
		}
	}
}
