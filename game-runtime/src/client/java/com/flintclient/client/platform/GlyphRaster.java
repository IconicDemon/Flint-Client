package com.flintclient.client.platform;

import com.flintclient.client.FlintClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Font Awesome 6 Free Solid glyphs (SIL OFL 1.1, {@code assets/flintclient/font/fa-solid-900.ttf}) rasterised with
 * stb_truetype into square white masks for the mask atlas ({@code render.MaskAtlas}): one em, the glyph centred across and on Font Awesome's
 * baseline, its edges antialiased. One font is read once for every size, where a Minecraft font set would be made per
 * size. Platform class (see {@link Clock}): engines without stb rasterise the same glyphs their own way. Render thread.
 */
public final class GlyphRaster {
	private static final String FILE = "/assets/flintclient/font/fa-solid-900.ttf";
	/** Font Awesome's em is 512 units, 448 of them above the baseline. */
	private static final float ASCENT = 448 / 512.0F;
	private static @Nullable STBTTFontinfo font;
	/** The file's bytes, kept for as long as stb reads from them. */
	private static @Nullable ByteBuffer data;
	private static boolean failed;

	private GlyphRaster() {
	}

	private static @Nullable STBTTFontinfo font() {
		if (font != null || failed) {
			return font;
		}
		try (InputStream in = GlyphRaster.class.getResourceAsStream(FILE)) {
			if (in == null) {
				throw new IOException("missing " + FILE);
			}
			byte[] bytes = in.readAllBytes();
			ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
			buffer.put(bytes).flip();
			STBTTFontinfo info = STBTTFontinfo.malloc();
			if (!STBTruetype.stbtt_InitFont(info, buffer)) {
				info.free();
				MemoryUtil.memFree(buffer);
				throw new IOException("stb_truetype couldn't read " + FILE);
			}
			data = buffer;
			font = info;
		} catch (IOException | RuntimeException e) {
			failed = true;
			FlintClient.LOGGER.warn("Flint: the menu's icon font couldn't be loaded; icons are left out", e);
		}
		return font;
	}

	/** {@code codepoint} as {@code size} × {@code size} ARGB pixels (white, the glyph's coverage as alpha); all clear if it can't be drawn. */
	public static int[] pixels(int codepoint, int size) {
		int[] argb = new int[size * size];
		STBTTFontinfo info = font();
		if (info == null || STBTruetype.stbtt_FindGlyphIndex(info, codepoint) == 0) {
			return argb;
		}
		float scale = STBTruetype.stbtt_ScaleForMappingEmToPixels(info, size);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var x0 = stack.mallocInt(1);
			var y0 = stack.mallocInt(1);
			var x1 = stack.mallocInt(1);
			var y1 = stack.mallocInt(1);
			STBTruetype.stbtt_GetCodepointBitmapBoxSubpixel(info, codepoint, scale, scale, 0, 0, x0, y0, x1, y1);
			int gw = x1.get(0) - x0.get(0);
			int gh = y1.get(0) - y0.get(0);
			if (gw <= 0 || gh <= 0) {
				return argb;
			}
			ByteBuffer bitmap = MemoryUtil.memAlloc(gw * gh);
			try {
				STBTruetype.stbtt_MakeCodepointBitmapSubpixel(info, bitmap, gw, gh, gw, scale, scale, 0, 0, codepoint);
				copy(bitmap, gw, gh, Math.round(ASCENT * size) + y0.get(0), argb, size);
			} finally {
				MemoryUtil.memFree(bitmap);
			}
		}
		return argb;
	}

	/** The glyph's {@code gw} × {@code gh} coverage into the em square, centred across and {@code top} pixels down. */
	private static void copy(ByteBuffer bitmap, int gw, int gh, int top, int[] argb, int size) {
		int left = (size - gw) / 2;
		for (int gy = 0; gy < gh; gy++) {
			int py = top + gy;
			if (py < 0 || py >= size) {
				continue;
			}
			for (int gx = 0; gx < gw; gx++) {
				int px = left + gx;
				if (px >= 0 && px < size) {
					int a = bitmap.get(gy * gw + gx) & 0xFF;
					argb[py * size + px] = a << 24 | 0xFFFFFF;
				}
			}
		}
	}
}
