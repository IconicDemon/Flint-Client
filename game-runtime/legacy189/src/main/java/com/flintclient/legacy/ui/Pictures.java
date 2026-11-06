package com.flintclient.legacy.ui;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * The mod cards' pictures, Flint's own ({@code assets/flintclient/textures/mods/<key>.png}, white masks): trimmed to their art,
 * fitted into the box they're shown in keeping their shape, and scaled once for that size by averaging the pixels each new
 * one covers, so they stay crisp at any window size. Tinted when drawn: grey while a mod is off, white while it's on.
 * Render thread.
 */
public final class Pictures {
	private static final Map<String, Picture> PICTURES = new HashMap<String, Picture>();

	private Pictures() {
	}

	private static final class Picture {
		final float[] coverage;
		final int w;
		final int h;
		/** A texture per size it has been shown at (a card, a page's header), at most a few. */
		final Map<Integer, Integer> textures = new HashMap<Integer, Integer>();

		Picture(float[] coverage, int w, int h) {
			this.coverage = coverage;
			this.w = w;
			this.h = h;
		}
	}

	/** Draws {@code key}'s picture centred on ({@code cx}, {@code cy}), as large as fits {@code maxW × maxH}; false if there is none. */
	public static boolean draw(String key, float cx, float cy, float maxW, float maxH, int argb) {
		Picture p = picture(key);
		if (p == null || maxW < 1 || maxH < 1) {
			return false;
		}
		float aspect = p.w / (float) p.h;
		int h = Math.max(1, Math.round(Math.min(maxH, maxW / aspect)));
		int w = Math.max(1, Math.round(h * aspect));
		Integer texture = p.textures.get(w * 4096 + h);
		if (texture == null) {
			if (p.textures.size() >= 4) {
				for (Integer old : p.textures.values()) {
					GL11.glDeleteTextures(old);
				}
				p.textures.clear();
			}
			texture = upload(p, w, h);
			p.textures.put(w * 4096 + h, texture);
		}
		float x = Math.round(cx - w / 2.0F);
		float y = Math.round(cy - h / 2.0F);
		Gfx.texture(texture, x, y, w, h, 0, 0, 1, 1, argb);
		return true;
	}

	/** Full-colour pictures by file (null for a missing one). */
	private static final Map<String, Art> ARTS = new HashMap<String, Art>();

	/** A full-colour picture: its pixels, the side of one art pixel in the file, and a texture per size shown. */
	private static final class Art {
		final int[] argb;
		final int w;
		final int h;
		final int cell;
		final Map<Integer, Integer> textures = new HashMap<Integer, Integer>();

		Art(int[] argb, int w, int h, int cell) {
			this.argb = argb;
			this.w = w;
			this.h = h;
			this.cell = cell;
		}
	}

	/**
	 * Draws the full-colour picture {@code file} ({@code textures/mods/<file>.png}, shown as it is) centred on
	 * ({@code cx}, {@code cy}), as large as fits {@code maxW × maxH}; false if there is none. Pixel art stays crisp: a whole
	 * number of pixels per art pixel when that comes close to the fitted size, else averaged down from the file.
	 */
	public static boolean drawArt(String file, float cx, float cy, float maxW, float maxH) {
		Art art = art(file);
		if (art == null || maxW < 1 || maxH < 1) {
			return false;
		}
		int nativeW = art.w / art.cell;
		int nativeH = art.h / art.cell;
		float fitted = Math.min(maxW / nativeW, maxH / nativeH);
		// A few percent over the box is worth whole art pixels.
		int whole = (int) Math.floor(fitted + 0.08F);
		int w;
		int h;
		if (whole >= 1 && whole >= fitted * 0.8F) {
			w = nativeW * whole;
			h = nativeH * whole;
		} else {
			w = Math.max(1, Math.round(nativeW * fitted));
			h = Math.max(1, Math.round(nativeH * fitted));
		}
		Integer texture = art.textures.get(w * 4096 + h);
		if (texture == null) {
			if (art.textures.size() >= 4) {
				for (Integer old : art.textures.values()) {
					GL11.glDeleteTextures(old);
				}
				art.textures.clear();
			}
			texture = uploadArt(art, w, h);
			art.textures.put(w * 4096 + h, texture);
		}
		Gfx.texture(texture, Math.round(cx - w / 2.0F), Math.round(cy - h / 2.0F), w, h, 0, 0, 1, 1, 0xFFFFFFFF);
		return true;
	}

	private static Art art(String file) {
		if (ARTS.containsKey(file)) {
			return ARTS.get(file);
		}
		Art art = null;
		try (InputStream in = Pictures.class.getResourceAsStream("/assets/flintclient/textures/mods/" + file + ".png")) {
			if (in != null) {
				BufferedImage image = ImageIO.read(in);
				int w = image.getWidth();
				int h = image.getHeight();
				int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
				art = new Art(argb, w, h, cell(argb, w, h));
			}
		} catch (Exception e) {
			org.apache.logging.log4j.LogManager.getLogger("Flint").warn("Flint couldn't read the picture " + file, e);
		}
		ARTS.put(file, art);
		return art;
	}

	/** The side of one art pixel in an upscaled pixel-art file: the largest size every run of equal pixels is a multiple of. */
	private static int cell(int[] argb, int w, int h) {
		int cell = gcd(w, h);
		for (int y = 0; y < h && cell > 1; y++) {
			int run = 1;
			for (int x = 1; x <= w; x++) {
				if (x < w && argb[y * w + x] == argb[y * w + x - 1]) {
					run++;
				} else {
					cell = gcd(cell, run);
					run = 1;
				}
			}
		}
		for (int x = 0; x < w && cell > 1; x++) {
			int run = 1;
			for (int y = 1; y <= h; y++) {
				if (y < h && argb[y * w + x] == argb[(y - 1) * w + x]) {
					run++;
				} else {
					cell = gcd(cell, run);
					run = 1;
				}
			}
		}
		return Math.max(1, cell);
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	/** The picture at {@code w × h}: whole art pixels repeated, or the file's pixels averaged by area (colour weighted by coverage). */
	private static int uploadArt(Art art, int w, int h) {
		int[] out = new int[w * h];
		int nativeW = art.w / art.cell;
		int nativeH = art.h / art.cell;
		if (w % nativeW == 0 && h % nativeH == 0 && w / nativeW == h / nativeH) {
			int k = w / nativeW;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					out[y * w + x] = art.argb[(y / k * art.cell + art.cell / 2) * art.w + x / k * art.cell + art.cell / 2];
				}
			}
		} else {
			float sx = art.w / (float) w;
			float sy = art.h / (float) h;
			for (int y = 0; y < h; y++) {
				float fy0 = y * sy;
				float fy1 = fy0 + sy;
				for (int x = 0; x < w; x++) {
					float fx0 = x * sx;
					float fx1 = fx0 + sx;
					float a = 0;
					float r = 0;
					float g = 0;
					float b = 0;
					float area = 0;
					for (int iy = (int) fy0; iy < Math.min(art.h, (int) Math.ceil(fy1)); iy++) {
						float wy = Math.min(fy1, iy + 1) - Math.max(fy0, iy);
						for (int ix = (int) fx0; ix < Math.min(art.w, (int) Math.ceil(fx1)); ix++) {
							float k = (Math.min(fx1, ix + 1) - Math.max(fx0, ix)) * wy;
							int p = art.argb[iy * art.w + ix];
							float pa = (p >>> 24) / 255.0F * k;
							a += pa;
							r += (p >> 16 & 0xFF) * pa;
							g += (p >> 8 & 0xFF) * pa;
							b += (p & 0xFF) * pa;
							area += k;
						}
					}
					int oa = area <= 0 ? 0 : Math.round(a / area * 255);
					int or = a <= 0 ? 0 : Math.round(r / a);
					int og = a <= 0 ? 0 : Math.round(g / a);
					int ob = a <= 0 ? 0 : Math.round(b / a);
					out[y * w + x] = oa << 24 | or << 16 | og << 8 | ob;
				}
			}
		}
		int texture = GL11.glGenTextures();
		GlStateManager.bindTexture(texture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		IntBuffer buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
		buffer.put(out).flip();
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
		return texture;
	}

	private static Picture picture(String key) {
		if (PICTURES.containsKey(key)) {
			return PICTURES.get(key);
		}
		Picture p = null;
		try (InputStream in = Pictures.class.getResourceAsStream("/assets/flintclient/textures/mods/" + key + ".png")) {
			if (in != null) {
				p = trim(ImageIO.read(in));
			}
		} catch (Exception e) {
			org.apache.logging.log4j.LogManager.getLogger("Flint").warn("Flint couldn't read the picture of " + key, e);
		}
		PICTURES.put(key, p);
		return p;
	}

	/** The picture's coverage (its alpha), cut to the rectangle its art fills. */
	private static Picture trim(BufferedImage image) {
		int iw = image.getWidth();
		int ih = image.getHeight();
		int[] argb = image.getRGB(0, 0, iw, ih, null, 0, iw);
		int x0 = iw;
		int y0 = ih;
		int x1 = -1;
		int y1 = -1;
		for (int y = 0; y < ih; y++) {
			for (int x = 0; x < iw; x++) {
				if ((argb[y * iw + x] >>> 24) > 10) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < 0) {
			return null;
		}
		int w = x1 - x0 + 1;
		int h = y1 - y0 + 1;
		float[] coverage = new float[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				coverage[y * w + x] = (argb[(y + y0) * iw + x + x0] >>> 24) / 255.0F;
			}
		}
		return new Picture(coverage, w, h);
	}

	/** Scales the coverage to {@code w × h} by area and uploads it as a white texture with smooth sampling. */
	private static int upload(Picture p, int w, int h) {
		int[] out = new int[w * h];
		float sx = p.w / (float) w;
		float sy = p.h / (float) h;
		for (int y = 0; y < h; y++) {
			float fy0 = y * sy;
			float fy1 = fy0 + sy;
			for (int x = 0; x < w; x++) {
				float fx0 = x * sx;
				float fx1 = fx0 + sx;
				float sum = 0;
				float area = 0;
				for (int iy = (int) fy0; iy < Math.min(p.h, (int) Math.ceil(fy1)); iy++) {
					float wy = Math.min(fy1, iy + 1) - Math.max(fy0, iy);
					for (int ix = (int) fx0; ix < Math.min(p.w, (int) Math.ceil(fx1)); ix++) {
						float wx = Math.min(fx1, ix + 1) - Math.max(fx0, ix);
						sum += p.coverage[iy * p.w + ix] * wx * wy;
						area += wx * wy;
					}
				}
				int a = area <= 0 ? 0 : Math.round(sum / area * 255);
				out[y * w + x] = (a << 24) | 0xFFFFFF;
			}
		}
		int texture = GL11.glGenTextures();
		GlStateManager.bindTexture(texture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		IntBuffer buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
		buffer.put(out).flip();
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
		return texture;
	}
}
