package com.flintclient.legacy.ui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * One texture holding every small shape Flint draws, rasterised once per pixel size: a solid white block (plain rectangles),
 * antialiased corner masks (filled and as rings) for rounded rectangles, Font Awesome glyphs and the Flint mark. Shapes are
 * white with their coverage in alpha (the mark keeps its colours) and are tinted by vertex colour, so a whole frame of the
 * menu draws from one texture. Entries are packed in shelves; when the texture is full it is cleared and refilled as shapes
 * are asked for again (a new window size). Render thread.
 */
public final class Atlas {
	public static final int SIZE = 1024;
	private static Atlas instance;

	private final int texture;
	private final Map<Long, Entry> entries = new HashMap<Long, Entry>();
	private final IntBuffer upload = ByteBuffer.allocateDirect(SIZE * 4 * 64).order(ByteOrder.nativeOrder()).asIntBuffer();
	private int shelfX;
	private int shelfY;
	private int shelfH;

	/** A shape's place in the atlas (pixels). */
	public static final class Entry {
		public final int x;
		public final int y;
		public final int w;
		public final int h;
		/** For a glyph: where its drawn pixels sit relative to the point it is centred on. */
		public int offsetX;
		public int offsetY;

		Entry(int x, int y, int w, int h) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}

		public float u0() {
			return x / (float) SIZE;
		}

		public float v0() {
			return y / (float) SIZE;
		}

		public float u1() {
			return (x + w) / (float) SIZE;
		}

		public float v1() {
			return (y + h) / (float) SIZE;
		}
	}

	private Atlas() {
		texture = GL11.glGenTextures();
		GlStateManager.bindTexture(texture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, SIZE, SIZE, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
		clear();
	}

	public static Atlas get() {
		if (instance == null) {
			instance = new Atlas();
		}
		return instance;
	}

	public int texture() {
		return texture;
	}

	private void clear() {
		entries.clear();
		shelfX = 0;
		shelfY = 0;
		shelfH = 0;
		// The solid block every plain rectangle samples from the middle of.
		int[] white = new int[16];
		java.util.Arrays.fill(white, 0xFFFFFFFF);
		Entry solid = place(4, 4, white);
		entries.put(key(KIND_SOLID, 0, 0, 0), solid);
	}

	// ---------- keys ----------

	static final int KIND_SOLID = 0;
	static final int KIND_CORNER = 1;
	static final int KIND_GLYPH = 2;
	static final int KIND_MARK = 3;

	static long key(int kind, int a, int b, int c) {
		return ((long) kind << 56) | ((long) (a & 0xFFFFF) << 36) | ((long) (b & 0xFFFF) << 20) | (c & 0xFFFFF);
	}

	/** The middle of the solid block, for plain fills. */
	public Entry solid() {
		return entries.get(key(KIND_SOLID, 0, 0, 0));
	}

	/**
	 * A top-left corner {@code r} pixels square of a rounded rectangle's fill ({@code ring} 0), or of a ring {@code ring}
	 * pixels thick along its edge. Coverage is the area of each pixel inside, sampled 4 × 4.
	 */
	public Entry corner(int r, int ring) {
		long k = key(KIND_CORNER, r, ring, 0);
		Entry e = entries.get(k);
		if (e == null) {
			int[] px = new int[r * r];
			for (int y = 0; y < r; y++) {
				for (int x = 0; x < r; x++) {
					int inside = 0;
					for (int sy = 0; sy < 4; sy++) {
						for (int sx = 0; sx < 4; sx++) {
							float dx = r - (x + (sx + 0.5F) / 4);
							float dy = r - (y + (sy + 0.5F) / 4);
							float d = (float) Math.sqrt(dx * dx + dy * dy);
							if (d <= r && (ring <= 0 || d >= r - ring)) {
								inside++;
							}
						}
					}
					px[y * r + x] = inside == 0 ? 0 : (Math.round(inside * 255 / 16.0F) << 24) | 0xFFFFFF;
				}
			}
			e = add(k, r, r, px);
		}
		return e;
	}

	/** An entry for {@code key}, if it was made already. */
	public Entry find(long key) {
		return entries.get(key);
	}

	/** Adds {@code w × h} ARGB pixels under {@code key}, clearing the atlas first when it is full. */
	public Entry add(long key, int w, int h, int[] argb) {
		// Shapes already queued are drawn first: a full atlas is cleared, which would move them.
		Gfx.flush();
		Entry e = place(w, h, argb);
		if (e == null) {
			clear();
			e = place(w, h, argb);
			if (e == null) {
				throw new IllegalStateException("shape too large for the atlas: " + w + "x" + h);
			}
		}
		entries.put(key, e);
		return e;
	}

	private Entry place(int w, int h, int[] argb) {
		if (w > SIZE || h > SIZE) {
			return null;
		}
		if (shelfX + w + 1 > SIZE) {
			shelfY += shelfH + 1;
			shelfX = 0;
			shelfH = 0;
		}
		if (shelfY + h > SIZE) {
			return null;
		}
		Entry e = new Entry(shelfX, shelfY, w, h);
		shelfX += w + 1;
		shelfH = Math.max(shelfH, h);
		if (w > 0 && h > 0) {
			IntBuffer buffer = w * h <= upload.capacity() ? upload : ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
			buffer.clear();
			buffer.put(argb, 0, w * h);
			buffer.flip();
			GlStateManager.bindTexture(texture);
			GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, e.x, e.y, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
		}
		return e;
	}
}
