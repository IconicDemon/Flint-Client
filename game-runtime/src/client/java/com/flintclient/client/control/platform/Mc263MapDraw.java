package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.MapQuads;
import com.flintclient.client.control.feature.map.MapRegion;
import com.flintclient.client.control.feature.map.MapWorld;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Draws the map's terrain for the minimap and the world map: every region texture under a view, cut to the view's shape
 * (a circle or a square, turned or not) as textured polygons, so the map is drawn straight from its cached textures each
 * frame with no off-screen pass. A region whose texture at the right detail isn't ready yet draws from a coarser or finer
 * one it has, so zooming and panning never show holes while textures fill in.
 *
 * <p>Points are in the canvas's pixels (the current pose maps them to the screen). Render thread; a few float arrays reused,
 * one small GUI element per region drawn.
 */
public final class Mc263MapDraw {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "map/white");
	private static boolean whiteMade;
	private static final float[] SUBJECT = new float[2 * 96];
	private static final float[] BUFFER = new float[2 * 96];
	private static final float[] UV = new float[2 * 96];
	private static final float[] AT = new float[2];

	private Mc263MapDraw() {
	}

	/**
	 * A view of the map: the world point at its centre, its turn, pixels per block, where its centre is on screen and the
	 * outline it is cut to (pixels, convex, clockwise on screen).
	 */
	public static final class View {
		double cx;
		double cz;
		float angle;
		float cos = 1;
		float sin;
		float scale = 1;
		float sx;
		float sy;
		final float[] clip = new float[2 * 72];
		int clipN;
		@Nullable ScreenRectangle scissor;
		boolean hidden;

		/** Centre on world ({@code x}, {@code z}) at screen ({@code screenX}, {@code screenY}), turned by {@code radians}. */
		public View at(double x, double z, float screenX, float screenY, float radians, float pixelsPerBlock) {
			cx = x;
			cz = z;
			sx = screenX;
			sy = screenY;
			angle = radians;
			cos = (float) Math.cos(radians);
			sin = (float) Math.sin(radians);
			scale = pixelsPerBlock;
			return this;
		}

		/** Cut to a circle of {@code radius} pixels round the centre. */
		public View circle(float radius) {
			int n = 64;
			clipN = n;
			for (int i = 0; i < n; i++) {
				double a = -2 * Math.PI * i / n;
				clip[2 * i] = sx + (float) Math.cos(a) * radius;
				clip[2 * i + 1] = sy + (float) Math.sin(a) * radius;
			}
			return this;
		}

		/** Cut to the rectangle [x0, x1) × [y0, y1). */
		public View rect(float x0, float y0, float x1, float y1) {
			clipN = 4;
			clip[0] = x0;
			clip[1] = y0;
			clip[2] = x0;
			clip[3] = y1;
			clip[4] = x1;
			clip[5] = y1;
			clip[6] = x1;
			clip[7] = y0;
			return this;
		}

		public float scale() {
			return scale;
		}

		public double centerX() {
			return cx;
		}

		public double centerZ() {
			return cz;
		}

		/** Where world ({@code x}, {@code z}) is on screen, into {@code out}. */
		public void toScreen(double x, double z, float[] out) {
			float dx = (float) (x - cx) * scale;
			float dz = (float) (z - cz) * scale;
			out[0] = sx + dx * cos - dz * sin;
			out[1] = sy + dx * sin + dz * cos;
		}

		/** The world point under screen ({@code px}, {@code py}), into {@code out} (x, z). */
		public void toWorld(float px, float py, double[] out) {
			float dx = px - sx;
			float dy = py - sy;
			out[0] = cx + (dx * cos + dy * sin) / scale;
			out[1] = cz + (-dx * sin + dy * cos) / scale;
		}
	}

	/**
	 * The scissor to give polygons drawn in the pixel box: null when the box is wholly inside Minecraft's current scissor (or
	 * there is none); the scissor's rectangle when it cuts the box; and {@code hidden} on the view when nothing of it shows.
	 * Minecraft keeps its scissor out of reach, so its edges are found by asking which GUI points it contains.
	 */
	static void scissor(GuiGraphicsExtractor g, View v, float x0, float y0, float x1, float y1, int guiScale) {
		v.hidden = false;
		v.scissor = null;
		int gx0 = (int) Math.floor(x0 / guiScale);
		int gy0 = (int) Math.floor(y0 / guiScale);
		int gx1 = (int) Math.ceil(x1 / guiScale) - 1;
		int gy1 = (int) Math.ceil(y1 / guiScale) - 1;
		if (g.containsPointInScissor(gx0, gy0) && g.containsPointInScissor(gx1, gy0) && g.containsPointInScissor(gx0, gy1)
			&& g.containsPointInScissor(gx1, gy1)) {
			return;
		}
		// A point inside, then each edge by halving.
		int ix = Integer.MIN_VALUE;
		int iy = 0;
		for (int j = 0; j <= 4 && ix == Integer.MIN_VALUE; j++) {
			for (int i = 0; i <= 4; i++) {
				int px = gx0 + (gx1 - gx0) * i / 4;
				int py = gy0 + (gy1 - gy0) * j / 4;
				if (g.containsPointInScissor(px, py)) {
					ix = px;
					iy = py;
					break;
				}
			}
		}
		if (ix == Integer.MIN_VALUE) {
			v.hidden = true;
			return;
		}
		int left = edge(g, ix, iy, gx0 - 1, true, true);
		int right = edge(g, ix, iy, gx1 + 1, true, false);
		int top = edge(g, ix, iy, gy0 - 1, false, true);
		int bottom = edge(g, ix, iy, gy1 + 1, false, false);
		v.scissor = new ScreenRectangle(left, top, right - left + 1, bottom - top + 1);
	}

	/** The last coordinate still inside the scissor going from {@code inside} toward {@code outside} along one axis. */
	private static int edge(GuiGraphicsExtractor g, int ix, int iy, int outside, boolean horizontal, boolean down) {
		int in = horizontal ? ix : iy;
		int out = outside;
		if (horizontal ? g.containsPointInScissor(out, iy) : g.containsPointInScissor(ix, out)) {
			return out;
		}
		while (Math.abs(out - in) > 1) {
			int mid = (in + out) / 2;
			boolean contains = horizontal ? g.containsPointInScissor(mid, iy) : g.containsPointInScissor(ix, mid);
			if (contains) {
				in = mid;
			} else {
				out = mid;
			}
		}
		return in;
	}

	/** The level of detail for a view: the coarsest whose pixels are still at least as big as the screen's. */
	static int lod(float pixelsPerBlock) {
		if (pixelsPerBlock >= 1) {
			return 0;
		}
		int lod = (int) Math.floor(Math.log(1 / pixelsPerBlock) / Math.log(2) + 1e-4);
		return Math.clamp(lod, 0, Mc263MapTiles.LODS - 1);
	}

	/**
	 * Draws {@code world}'s layer under {@code v}, tinted {@code argb}. {@code build}: textures may be made and updated this
	 * frame (within the budget set for it).
	 */
	static void terrain(GuiGraphicsExtractor g, View v, MapWorld world, int layer, int argb, boolean build) {
		if (v.hidden || v.clipN < 3) {
			return;
		}
		// The world area under the outline.
		double minX = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		double[] w = new double[2];
		for (int i = 0; i < v.clipN; i++) {
			v.toWorld(v.clip[2 * i], v.clip[2 * i + 1], w);
			minX = Math.min(minX, w[0]);
			minZ = Math.min(minZ, w[1]);
			maxX = Math.max(maxX, w[0]);
			maxZ = Math.max(maxZ, w[1]);
		}
		int rx0 = (int) Math.floor(minX) >> MapRegion.BITS;
		int rz0 = (int) Math.floor(minZ) >> MapRegion.BITS;
		int rx1 = (int) Math.floor(maxX) >> MapRegion.BITS;
		int rz1 = (int) Math.floor(maxZ) >> MapRegion.BITS;
		if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 6000) {
			return;
		}
		int lod = lod(v.scale);
		// Nearest regions first, so the budget goes where you look.
		int crx = (int) Math.floor(v.cx) >> MapRegion.BITS;
		int crz = (int) Math.floor(v.cz) >> MapRegion.BITS;
		int rings = Math.max(Math.max(crx - rx0, rx1 - crx), Math.max(crz - rz0, rz1 - crz));
		for (int ring = 0; ring <= rings; ring++) {
			for (int rz = crz - ring; rz <= crz + ring; rz++) {
				for (int rx = crx - ring; rx <= crx + ring; rx++) {
					if (Math.max(Math.abs(rx - crx), Math.abs(rz - crz)) != ring || rx < rx0 || rx > rx1 || rz < rz0 || rz > rz1) {
						continue;
					}
					region(g, v, world, layer, rx, rz, lod, argb, build);
				}
			}
		}
	}

	private static void region(GuiGraphicsExtractor g, View v, MapWorld world, int layer, int rx, int rz, int lod, int argb, boolean build) {
		Mc263MapTiles.Tile tile = Mc263MapTiles.tile(world, layer, rx, rz, lod, build);
		if (tile == null || tile.buildRow < tile.size && tile.builtLook < 0) {
			// Not ready at this detail: whatever this region has at another (finer first, then coarser).
			Mc263MapTiles.Tile other = null;
			for (int d = 1; d < Mc263MapTiles.LODS && other == null; d++) {
				if (lod - d >= 0) {
					other = ready(Mc263MapTiles.tile(world, layer, rx, rz, lod - d, false));
				}
				if (other == null && lod + d < Mc263MapTiles.LODS) {
					other = ready(Mc263MapTiles.tile(world, layer, rx, rz, lod + d, false));
				}
			}
			if (other != null) {
				quad(g, v, other.id, rx, rz, argb);
			}
			if (tile == null) {
				return;
			}
		}
		quad(g, v, tile.id, rx, rz, argb);
	}

	private static Mc263MapTiles.@Nullable Tile ready(Mc263MapTiles.@Nullable Tile tile) {
		return tile != null && tile.builtLook >= 0 ? tile : null;
	}

	/** One region's texture, cut to the view's outline. */
	private static void quad(GuiGraphicsExtractor g, View v, Identifier texture, int rx, int rz, int argb) {
		double x0 = (double) rx * MapRegion.SIZE;
		double z0 = (double) rz * MapRegion.SIZE;
		int n = 4;
		v.toScreen(x0, z0, AT);
		SUBJECT[0] = AT[0];
		SUBJECT[1] = AT[1];
		v.toScreen(x0, z0 + MapRegion.SIZE, AT);
		SUBJECT[2] = AT[0];
		SUBJECT[3] = AT[1];
		v.toScreen(x0 + MapRegion.SIZE, z0 + MapRegion.SIZE, AT);
		SUBJECT[4] = AT[0];
		SUBJECT[5] = AT[1];
		v.toScreen(x0 + MapRegion.SIZE, z0, AT);
		SUBJECT[6] = AT[0];
		SUBJECT[7] = AT[1];
		n = clip(SUBJECT, n, v.clip, v.clipN);
		if (n < 3) {
			return;
		}
		double[] w = new double[2];
		for (int i = 0; i < n; i++) {
			v.toWorld(SUBJECT[2 * i], SUBJECT[2 * i + 1], w);
			UV[2 * i] = (float) ((w[0] - x0) / MapRegion.SIZE);
			UV[2 * i + 1] = (float) ((w[1] - z0) / MapRegion.SIZE);
		}
		orient(SUBJECT, UV, n);
		MapQuads.polygon(g, texture, SUBJECT, UV, n, argb, v.scissor);
	}

	/** A plain filled convex polygon ({@code n} points, pixels) in {@code argb}: arrows and markers. */
	public static void shape(GuiGraphicsExtractor g, float[] points, int n, int argb, @Nullable ScreenRectangle scissor) {
		white();
		for (int i = 0; i < n; i++) {
			SUBJECT[2 * i] = points[2 * i];
			SUBJECT[2 * i + 1] = points[2 * i + 1];
			UV[2 * i] = 0.5F;
			UV[2 * i + 1] = 0.5F;
		}
		orient(SUBJECT, UV, n);
		MapQuads.polygon(g, WHITE, SUBJECT, UV, n, argb, scissor);
	}

	/** A plain filled convex polygon cut to {@code v}'s outline first (grid lines, slime chunks, areas). */
	static void clipped(GuiGraphicsExtractor g, View v, float[] points, int n, int argb) {
		if (v.hidden) {
			return;
		}
		float[] copy = CLIPPED;
		System.arraycopy(points, 0, copy, 0, 2 * n);
		int m = clip(copy, n, v.clip, v.clipN);
		if (m >= 3) {
			shape(g, copy, m, argb, v.scissor);
		}
	}

	private static final float[] CLIPPED = new float[2 * 96];

	private static void white() {
		if (!whiteMade) {
			whiteMade = true;
			NativeImage image = new NativeImage(4, 4, false);
			image.fillRect(0, 0, 4, 4, 0xFFFFFFFF);
			Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(() -> "Flint map white", image));
		}
	}

	/** Puts the points in the order Minecraft's own quads use on screen (as a rectangle's: left top, left bottom, …). */
	private static void orient(float[] xy, float[] uv, int n) {
		float area = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			area += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1];
		}
		if (area <= 0) {
			return;
		}
		// The other way round, keeping the first point first (the fan's centre: an arrow's tip).
		for (int i = 1, j = n - 1; i < j; i++, j--) {
			swap(xy, i, j);
			swap(uv, i, j);
		}
	}

	private static void swap(float[] a, int i, int j) {
		float x = a[2 * i];
		float y = a[2 * i + 1];
		a[2 * i] = a[2 * j];
		a[2 * i + 1] = a[2 * j + 1];
		a[2 * j] = x;
		a[2 * j + 1] = y;
	}

	/** Whether every point of {@code points} ({@code n}) is inside the convex polygon {@code poly} ({@code m} points), either winding. */
	private static boolean inside(float[] points, int n, float[] poly, int m) {
		float area = 0;
		for (int i = 0; i < m; i++) {
			int j = (i + 1) % m;
			area += poly[2 * i] * poly[2 * j + 1] - poly[2 * j] * poly[2 * i + 1];
		}
		float sign = area >= 0 ? 1 : -1;
		for (int e = 0; e < m; e++) {
			float ax = poly[2 * e];
			float ay = poly[2 * e + 1];
			float bx = poly[2 * ((e + 1) % m)];
			float by = poly[2 * ((e + 1) % m) + 1];
			for (int i = 0; i < n; i++) {
				if (sign * ((bx - ax) * (points[2 * i + 1] - ay) - (by - ay) * (points[2 * i] - ax)) < 0) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Cuts the convex polygon {@code subject} ({@code n} points) to the convex polygon {@code clip} (Sutherland–Hodgman), in
	 * place; returns the new point count. Works for either winding of the clip polygon.
	 */
	static int clip(float[] subject, int n, float[] clip, int clipN) {
		float area = 0;
		for (int i = 0; i < clipN; i++) {
			int j = (i + 1) % clipN;
			area += clip[2 * i] * clip[2 * j + 1] - clip[2 * j] * clip[2 * i + 1];
		}
		float sign = area >= 0 ? 1 : -1;
		// The usual cases cost little: the outline wholly inside the subject (a region bigger than the minimap) is the
		// answer; a subject wholly inside the outline stays as it is.
		if (inside(clip, clipN, subject, n)) {
			System.arraycopy(clip, 0, subject, 0, 2 * clipN);
			return clipN;
		}
		if (inside(subject, n, clip, clipN)) {
			return n;
		}
		float[] in = subject;
		float[] out = BUFFER;
		for (int e = 0; e < clipN && n > 0; e++) {
			float ax = clip[2 * e];
			float ay = clip[2 * e + 1];
			float bx = clip[2 * ((e + 1) % clipN)];
			float by = clip[2 * ((e + 1) % clipN) + 1];
			int m = 0;
			for (int i = 0; i < n; i++) {
				float px = in[2 * i];
				float py = in[2 * i + 1];
				float qx = in[2 * ((i + 1) % n)];
				float qy = in[2 * ((i + 1) % n) + 1];
				float dp = sign * ((bx - ax) * (py - ay) - (by - ay) * (px - ax));
				float dq = sign * ((bx - ax) * (qy - ay) - (by - ay) * (qx - ax));
				if (dp >= 0) {
					if (m < 95) {
						out[2 * m] = px;
						out[2 * m + 1] = py;
						m++;
					}
				}
				if (dp >= 0 != dq >= 0 && m < 95) {
					float t = dp / (dp - dq);
					out[2 * m] = px + (qx - px) * t;
					out[2 * m + 1] = py + (qy - py) * t;
					m++;
				}
			}
			float[] swap = in;
			in = out;
			out = swap;
			n = m;
		}
		if (in != subject) {
			System.arraycopy(in, 0, subject, 0, 2 * n);
		}
		return n;
	}
}
