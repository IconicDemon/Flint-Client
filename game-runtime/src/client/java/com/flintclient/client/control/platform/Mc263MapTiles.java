package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.map.MapRegion;
import com.flintclient.client.control.feature.map.MapShading;
import com.flintclient.client.control.feature.map.MapWorld;
import com.flintclient.client.graphics.GpuResourceTracker;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The map's textures: one per region and level of detail, shaded with the map's look. Level 0 is a block per pixel (512²);
 * each level above halves it, and levels 3 and up come from the region's preview, so far-out views never need the full
 * data. Shared by the minimap and the world map.
 *
 * <ul>
 * <li>A texture is built a band of rows at a time within the frame's time budget (it fills in from the top the first time
 * it is seen), then kept: when the scanner changes a chunk, only that chunk's pixels are shaded again and only their rows
 * are uploaded. Nothing is redrawn on frames where nothing changed.</li>
 * <li>Changing the look (shading, height tint, colours) rebuilds the textures the same way, a band at a time.</li>
 * <li>Textures unused for a few seconds, or past the memory budget, are freed (least recently drawn first).</li>
 * </ul>
 * Render thread.
 */
final class Mc263MapTiles {
	static final int LODS = 6;
	private static final long KEEP_NANOS = 8_000_000_000L;
	private static final long MAX_BYTES = 192L << 20;

	static final class Tile {
		final MapWorld world;
		final int layer;
		final int rx;
		final int rz;
		final int lod;
		final int size;
		final Identifier id;
		final DynamicTexture texture;
		final NativeImage pixels;
		int builtRevision = -1;
		int builtLook = -1;
		int westRevision = -2;
		int northRevision = -2;
		/** The next row a first build (or a rebuild for a new look) shades; size when done. */
		int buildRow;
		/** The region's revision when the build in progress started, and the look it is building. */
		int buildFrom;
		int buildingLook = -1;
		long lastUse;

		Tile(MapWorld world, int layer, int rx, int rz, int lod, Identifier id) {
			this.world = world;
			this.layer = layer;
			this.rx = rx;
			this.rz = rz;
			this.lod = lod;
			this.size = MapRegion.SIZE >> lod;
			this.id = id;
			this.pixels = new NativeImage(size, size, true);
			this.texture = new DynamicTexture(() -> "Flint map " + rx + "," + rz + "@" + lod, pixels);
		}

		/** Whether anything of it has been drawn into the texture. */
		boolean started() {
			return buildRow > 0 || builtRevision >= 0;
		}

		long bytes() {
			return 2L * size * size * 4;
		}
	}

	private static final Map<Long, Tile> TILES = new HashMap<>();
	private static MapShading.Look look = MapShading.Look.DEFAULT;
	/** The look of the texture being brought up to date (a cave layer's runs its terrain depth down the layer). */
	private static MapShading.Look current = MapShading.Look.DEFAULT;
	private static int lookRevision;
	private static long deadline;
	private static long bytes;
	private static int made;
	private static long lastEvict;

	private Mc263MapTiles() {
	}

	private static long key(int layer, int rx, int rz, int lod) {
		return ((long) (rx & 0x0FFFFFFF) << 32) | (long) (rz & 0x0FFFFFFF) << 4 | (long) layer << 3 | lod;
	}

	/** Sets the look; a new look rebuilds every texture (a band at a time). */
	static void look(MapShading.Look value) {
		if (!value.equals(look)) {
			look = value;
			lookRevision++;
		}
	}

	/** Starts a frame's work: textures may be built or updated for {@code budgetNanos} from now. */
	static void budget(long budgetNanos) {
		deadline = System.nanoTime() + budgetNanos;
	}

	private static boolean timeLeft() {
		return System.nanoTime() < deadline;
	}

	/**
	 * The texture of region ({@code rx}, {@code rz}) at level {@code lod} of {@code world}'s layer {@code layer}, brought up to
	 * date as far as the budget allows; null when the region's data isn't in memory yet (it is asked for) or nothing of it is
	 * drawn yet. {@code build}: false only uses what exists.
	 */
	static @Nullable Tile tile(MapWorld world, int layer, int rx, int rz, int lod, boolean build) {
		long key = key(layer, rx, rz, lod);
		Tile tile = TILES.get(key);
		long now = System.nanoTime();
		if (tile != null && tile.world != world) {
			free(tile);
			TILES.remove(key);
			tile = null;
		}
		MapRegion region = world.request(rx, rz, lod < 3);
		if (region == null) {
			return tile != null && tile.started() ? touch(tile, now) : null;
		}
		if (tile == null) {
			if (!build || !timeLeft() || region.empty()) {
				return null;
			}
			Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "map/" + (made++) + "_" + lod);
			tile = new Tile(world, layer, rx, rz, lod, id);
			Minecraft.getInstance().getTextureManager().register(id, tile.texture);
			TILES.put(key, tile);
			bytes += tile.bytes();
			GpuResourceTracker.get().register(GpuResourceTracker.Kind.PREVIEW, tile.bytes() / 2);
		}
		touch(tile, now);
		if (build) {
			update(tile, region);
		}
		return tile.started() ? tile : null;
	}

	private static Tile touch(Tile tile, long now) {
		tile.lastUse = now;
		return tile;
	}

	/** Brings a texture up to date: a first build or new look a band at a time, else only the chunks that changed. */
	private static void update(Tile tile, MapRegion region) {
		current = look(tile.world);
		if (tile.lod >= 3) {
			region.updatePreview();
		}
		if (tile.builtLook != lookRevision) {
			// A first build, or a new look: from the top, as far as time allows.
			if (tile.buildRow >= tile.size || tile.buildingLook != lookRevision) {
				// Done with an older look, or the look changed mid-build: from the top again.
				tile.buildRow = 0;
				tile.buildingLook = lookRevision;
			}
			int start = tile.buildRow;
			if (start == 0) {
				tile.buildFrom = region.revision();
			}
			while (tile.buildRow < tile.size && timeLeft()) {
				int end = Math.min(tile.size, tile.buildRow + Math.max(8, tile.size / 16));
				for (int py = tile.buildRow; py < end; py++) {
					for (int px = 0; px < tile.size; px++) {
						tile.pixels.setPixel(px, py, pixel(tile, region, px, py));
					}
				}
				tile.buildRow = end;
			}
			if (tile.buildRow > start) {
				upload(tile, start, tile.buildRow);
			}
			if (tile.buildRow >= tile.size) {
				tile.builtLook = lookRevision;
				// Chunks that changed while it was being built are shaded again next.
				tile.builtRevision = tile.buildFrom;
				tile.westRevision = neighbourRevision(tile, -1, 0);
				tile.northRevision = neighbourRevision(tile, 0, -1);
			}
			return;
		}
		int west = neighbourRevision(tile, -1, 0);
		int north = neighbourRevision(tile, 0, -1);
		if (tile.lod == 0 && (west != tile.westRevision || north != tile.northRevision) && timeLeft()) {
			// A neighbour arrived or changed: the edge columns' slopes read its heights.
			tile.westRevision = west;
			tile.northRevision = north;
			for (int i = 0; i < tile.size; i++) {
				tile.pixels.setPixel(0, i, pixel(tile, region, 0, i));
				tile.pixels.setPixel(i, 0, pixel(tile, region, i, 0));
			}
			upload(tile, 0, tile.size);
		}
		if (region.revision() == tile.builtRevision || !timeLeft()) {
			return;
		}
		int since = tile.builtRevision;
		int top = Integer.MAX_VALUE;
		int bottom = -1;
		int shift = tile.lod;
		for (int cz = 0; cz < MapRegion.CHUNKS; cz++) {
			for (int cx = 0; cx < MapRegion.CHUNKS; cx++) {
				if (region.chunkRevision(cx, cz) <= since) {
					continue;
				}
				// The chunk's pixels, and one more right and down (their slopes read this chunk's heights).
				int x0 = (cx << 4) >> shift;
				int z0 = (cz << 4) >> shift;
				int x1 = Math.min(tile.size, (((cx << 4) + 16 + (1 << shift) - 1) >> shift) + 1);
				int z1 = Math.min(tile.size, (((cz << 4) + 16 + (1 << shift) - 1) >> shift) + 1);
				for (int py = z0; py < z1; py++) {
					for (int px = x0; px < x1; px++) {
						tile.pixels.setPixel(px, py, pixel(tile, region, px, py));
					}
				}
				top = Math.min(top, z0);
				bottom = Math.max(bottom, z1);
			}
		}
		tile.builtRevision = region.revision();
		if (bottom > top) {
			upload(tile, top, bottom);
		}
	}

	private static int neighbourRevision(Tile tile, int dx, int dz) {
		MapRegion n = tile.world.region(tile.rx + dx, tile.rz + dz);
		return n == null || !n.full() ? -1 : n.revision();
	}

	private static void upload(Tile tile, int top, int bottom) {
		if (top == 0 && bottom >= tile.size) {
			tile.texture.upload();
		} else {
			com.flintclient.client.compat.McCompat.writeRows(tile.texture.getTexture(), tile.pixels, top, bottom);
		}
	}

	// ---------- shading ----------

	/** Texture pixel ({@code px}, {@code py}) of {@code tile}: one shaded column, or the average of the ones it covers. */
	private static int pixel(Tile tile, MapRegion region, int px, int py) {
		if (tile.lod == 0) {
			return shade(tile, region, px, py);
		}
		if (tile.lod < 3) {
			int step = 1 << tile.lod;
			return average(tile, region, px * step, py * step, step, false);
		}
		int step = 1 << (tile.lod - 3);
		return average(tile, region, px * step, py * step, step, true);
	}

	private static int average(Tile tile, MapRegion region, int x0, int z0, int step, boolean preview) {
		int r = 0;
		int g = 0;
		int b = 0;
		int n = 0;
		for (int z = z0; z < z0 + step; z++) {
			for (int x = x0; x < x0 + step; x++) {
				int argb = preview ? shadePreview(region, x, z) : shade(tile, region, x, z);
				if (argb == 0) {
					continue;
				}
				r += argb >> 16 & 255;
				g += argb >> 8 & 255;
				b += argb & 255;
				n++;
			}
		}
		if (n == 0) {
			return 0;
		}
		int alpha = n * 255 / (step * step);
		return alpha << 24 | (r / n) << 16 | (g / n) << 8 | b / n;
	}

	/** The look for {@code world}'s textures: a cave layer's knows where it was read from (its terrain depth runs down it). */
	private static MapShading.Look look(MapWorld world) {
		return world.look(look);
	}

	/**
	 * One column shaded, its slope read from its west, north and north-west neighbours (Xaero's 3D light needs the
	 * diagonal one), in the next regions at the edges.
	 */
	private static int shade(Tile tile, MapRegion region, int lx, int lz) {
		int c = region.color(lx, lz);
		if (c >>> 24 == MapRegion.UNKNOWN) {
			return 0;
		}
		int h = region.height(lx, lz);
		int hw = height(tile, region, lx - 1, lz, h);
		int hn = height(tile, region, lx, lz - 1, h);
		int hnw = height(tile, region, lx - 1, lz - 1, hn + hw - h);
		return MapShading.pixel(c, h, hw, hn, hnw, current);
	}

	/** The height of column ({@code lx}, {@code lz}) of the region or a neighbour (one step out); {@code otherwise} when unknown. */
	private static int height(Tile tile, MapRegion region, int lx, int lz, int otherwise) {
		MapRegion r = region;
		if (lx < 0 || lz < 0) {
			r = tile.world.region(tile.rx - (lx < 0 ? 1 : 0), tile.rz - (lz < 0 ? 1 : 0));
			if (r == null || !r.full()) {
				return otherwise;
			}
			lx &= MapRegion.SIZE - 1;
			lz &= MapRegion.SIZE - 1;
		}
		return r.color(lx, lz) >>> 24 == MapRegion.UNKNOWN ? otherwise : r.height(lx, lz);
	}

	/** One preview pixel (8 × 8 blocks) shaded, its slope per block from its neighbours' average heights. */
	private static int shadePreview(MapRegion region, int px, int pz) {
		int c = region.previewColor(px, pz);
		if (c >>> 24 == MapRegion.UNKNOWN) {
			return 0;
		}
		int h = region.previewHeight(px, pz);
		int hw = h;
		int hn = h;
		if (px > 0 && region.previewColor(px - 1, pz) >>> 24 != MapRegion.UNKNOWN) {
			hw = h + Math.round((region.previewHeight(px - 1, pz) - h) / (float) MapRegion.PREVIEW_STEP * 2);
		}
		if (pz > 0 && region.previewColor(px, pz - 1) >>> 24 != MapRegion.UNKNOWN) {
			hn = h + Math.round((region.previewHeight(px, pz - 1) - h) / (float) MapRegion.PREVIEW_STEP * 2);
		}
		return MapShading.pixel(c, h, hw, hn, current);
	}

	// ---------- memory ----------

	/** Frees textures unused for a while, and the least recently used past the budget. At most a few times a second. */
	static void evict() {
		long now = System.nanoTime();
		if (now - lastEvict < 250_000_000L) {
			return;
		}
		lastEvict = now;
		Iterator<Tile> it = TILES.values().iterator();
		while (it.hasNext()) {
			Tile tile = it.next();
			if (now - tile.lastUse > KEEP_NANOS) {
				free(tile);
				it.remove();
			}
		}
		if (bytes > MAX_BYTES) {
			List<Tile> byAge = new ArrayList<>(TILES.values());
			byAge.sort(java.util.Comparator.comparingLong(t -> t.lastUse));
			for (Tile tile : byAge) {
				if (bytes <= MAX_BYTES * 3 / 4 || now - tile.lastUse < 100_000_000L) {
					break;
				}
				free(tile);
				TILES.remove(key(tile.layer, tile.rx, tile.rz, tile.lod));
			}
		}
	}

	private static void free(Tile tile) {
		Minecraft.getInstance().getTextureManager().release(tile.id);
		bytes -= tile.bytes();
		GpuResourceTracker.get().release(GpuResourceTracker.Kind.PREVIEW, tile.bytes() / 2);
	}

	/** Frees every texture of {@code world} (null: all; the world was left). */
	static void release(@Nullable MapWorld world) {
		Iterator<Tile> it = TILES.values().iterator();
		while (it.hasNext()) {
			Tile tile = it.next();
			if (world == null || tile.world == world) {
				free(tile);
				it.remove();
			}
		}
	}

	/** For the profile line: textures alive and their bytes (CPU and GPU together). */
	static String stats() {
		return TILES.size() + " map textures, " + (bytes >> 20) + " MB";
	}
}
