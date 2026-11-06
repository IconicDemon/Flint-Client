package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class CapePngTest {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	/** A cape-like 64×32 picture: a gradient front, a clear outside. */
	static int[] capePixels(int width, int height, int seed) {
		int[] argb = new int[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean inside = x < width * 22 / 64 && y < height * 17 / 32;
				argb[y * width + x] = inside ? 0xFF000000 | (x * 4 + seed & 0xFF) << 16 | (y * 8 & 0xFF) << 8 | seed * 31 & 0xFF : 0;
			}
		}
		return argb;
	}

	private static byte[] header(int width, int height, int depth, int colour, int interlace) {
		byte[] h = new byte[13];
		put32(h, 0, width);
		put32(h, 4, height);
		h[8] = (byte) depth;
		h[9] = (byte) colour;
		h[12] = (byte) interlace;
		return h;
	}

	private static void put32(byte[] b, int at, int v) {
		b[at] = (byte) (v >>> 24);
		b[at + 1] = (byte) (v >>> 16);
		b[at + 2] = (byte) (v >>> 8);
		b[at + 3] = (byte) v;
	}

	private static byte[] deflate(byte[] raw) {
		Deflater d = new Deflater();
		d.setInput(raw);
		d.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		while (!d.finished()) {
			out.write(buffer, 0, d.deflate(buffer));
		}
		d.end();
		return out.toByteArray();
	}

	/** Rows of {@code bpp}-byte pixels, each filtered with {@code filters[y % filters.length]}, as PNG's raw image data. */
	private static byte[] filtered(byte[][] rows, int bpp, int[] filters) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int y = 0; y < rows.length; y++) {
			int f = filters[y % filters.length];
			byte[] row = rows[y];
			byte[] prev = y == 0 ? new byte[row.length] : rows[y - 1];
			out.write(f);
			for (int i = 0; i < row.length; i++) {
				int x = row[i] & 0xFF;
				int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
				int b = prev[i] & 0xFF;
				int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
				int predicted = switch (f) {
					case 0 -> 0;
					case 1 -> a;
					case 2 -> b;
					case 3 -> a + b >>> 1;
					default -> {
						int p = a + b - c;
						int pa = Math.abs(p - a);
						int pb = Math.abs(p - b);
						int pc = Math.abs(p - c);
						yield pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
					}
				};
				out.write(x - predicted & 0xFF);
			}
		}
		return out.toByteArray();
	}

	private static byte[] file(byte[] ihdr, byte[]... chunks) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(SIGNATURE, 0, SIGNATURE.length);
		CapePng.chunk(out, "IHDR", ihdr);
		for (int i = 0; i + 1 < chunks.length; i += 2) {
			CapePng.chunk(out, new String(chunks[i], java.nio.charset.StandardCharsets.ISO_8859_1), chunks[i + 1]);
		}
		CapePng.chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static byte[] type(String t) {
		return t.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	private static byte[][] rgbaRows(int width, int height, int[] argb) {
		byte[][] rows = new byte[height][width * 4];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = argb[y * width + x];
				rows[y][x * 4] = (byte) (p >>> 16);
				rows[y][x * 4 + 1] = (byte) (p >>> 8);
				rows[y][x * 4 + 2] = (byte) p;
				rows[y][x * 4 + 3] = (byte) (p >>> 24);
			}
		}
		return rows;
	}

	@Test
	void anRgbaCapeDecodesToItsPixels() throws Exception {
		int[] argb = capePixels(64, 32, 7);
		CapePng.Image image = CapePng.decode(CapePng.encodeRgba(64, 32, argb), 1024, 512);
		assertEquals(64, image.width());
		assertEquals(32, image.height());
		assertArrayEquals(argb, image.argb());
	}

	@Test
	void everyRowFilterIsUndone() throws Exception {
		int[] argb = capePixels(128, 64, 3);
		byte[] png = file(header(128, 64, 8, 6, 0), type("IDAT"), deflate(filtered(rgbaRows(128, 64, argb), 4, new int[] {0, 1, 2, 3, 4})));
		assertArrayEquals(argb, CapePng.decode(png, 1024, 512).argb());
	}

	@Test
	void imageDataSplitOverSeveralChunksIsJoined() throws Exception {
		int[] argb = capePixels(64, 32, 9);
		byte[] data = deflate(filtered(rgbaRows(64, 32, argb), 4, new int[] {4}));
		int third = data.length / 3;
		byte[] png = file(header(64, 32, 8, 6, 0), type("IDAT"), Arrays.copyOfRange(data, 0, third), type("IDAT"),
			Arrays.copyOfRange(data, third, 2 * third), type("IDAT"), Arrays.copyOfRange(data, 2 * third, data.length));
		assertArrayEquals(argb, CapePng.decode(png, 1024, 512).argb());
	}

	@Test
	void paletteGreyAndRgbImagesDecode() throws Exception {
		// Palette with transparency: index 0 clear, 1 red, 2 half-transparent blue.
		byte[][] rows = new byte[32][64];
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				rows[y][x] = (byte) ((x + y) % 3);
			}
		}
		byte[] plte = {0, 0, 0, (byte) 255, 0, 0, 0, 0, (byte) 255};
		byte[] trns = {0, (byte) 255, (byte) 128};
		CapePng.Image palette = CapePng.decode(file(header(64, 32, 8, 3, 0), type("PLTE"), plte, type("tRNS"), trns, type("IDAT"),
			deflate(filtered(rows, 1, new int[] {1, 2}))), 1024, 512);
		assertEquals(0x00000000, palette.argb()[0]);
		assertEquals(0xFFFF0000, palette.argb()[1]);
		assertEquals(0x800000FF, palette.argb()[2]);
		// Grey with alpha.
		byte[][] ga = new byte[32][128];
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				ga[y][x * 2] = (byte) (x * 4);
				ga[y][x * 2 + 1] = (byte) (y * 8);
			}
		}
		CapePng.Image grey = CapePng.decode(file(header(64, 32, 8, 4, 0), type("IDAT"), deflate(filtered(ga, 2, new int[] {3}))), 1024, 512);
		assertEquals(0x00080808, grey.argb()[2], "x 2, y 0: grey 8, alpha 0");
		assertEquals(0x08080808, grey.argb()[64 + 2], "x 2, y 1: grey 8, alpha 8");
		// RGB with a transparent colour key (pure green).
		byte[][] rgb = new byte[32][192];
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				rgb[y][x * 3 + 1] = (byte) (x == 0 ? 255 : 10);
			}
		}
		CapePng.Image keyed = CapePng.decode(file(header(64, 32, 8, 2, 0), type("tRNS"), new byte[] {0, 0, 0, (byte) 255, 0, 0}, type("IDAT"),
			deflate(filtered(rgb, 3, new int[] {0}))), 1024, 512);
		assertEquals(0x0000FF00, keyed.argb()[0]);
		assertEquals(0xFF000A00, keyed.argb()[1]);
	}

	@Test
	void ancillaryChunksAreSkipped() throws Exception {
		int[] argb = capePixels(64, 32, 1);
		byte[] png = file(header(64, 32, 8, 6, 0), type("tEXt"), type("Comment\0hello"), type("IDAT"),
			deflate(filtered(rgbaRows(64, 32, argb), 4, new int[] {0})), type("tIME"), new byte[7]);
		assertArrayEquals(argb, CapePng.decode(png, 1024, 512).argb());
	}

	private static void refused(byte[] png, String reason) {
		CapePng.Refused e = assertThrows(CapePng.Refused.class, () -> CapePng.decode(png, 1024, 512));
		assertTrue(e.getMessage().contains(reason), e.getMessage() + " (expected: " + reason + ")");
	}

	@Test
	void whatIsntAUsablePngIsRefusedWithAReason() {
		byte[] good = CapePng.encodeRgba(64, 32, capePixels(64, 32, 2));
		refused("GIF89a not at all a png file, padded to be long enough".getBytes(), "not a PNG");
		refused(Arrays.copyOf(good, 20), "too short");
		byte[] corrupt = good.clone();
		corrupt[40] ^= 0x55;
		refused(corrupt, "checksum");
		refused(Arrays.copyOf(good, good.length - 6), "truncated");
		refused(file(header(64, 32, 16, 6, 0), type("IDAT"), deflate(new byte[32 * (1 + 64 * 8)])), "8-bit");
		refused(file(header(64, 32, 8, 6, 1), type("IDAT"), deflate(new byte[32 * (1 + 64 * 4)])), "interlaced");
		refused(file(header(64, 32, 8, 5, 0), type("IDAT"), deflate(new byte[10])), "colour type");
		refused(file(header(64, 32, 8, 3, 0), type("IDAT"), deflate(new byte[32 * 65])), "without a palette");
		refused(file(header(64, 32, 8, 6, 0), type("IDAT"), deflate(new byte[32 * (1 + 64 * 4) - 10])), "short");
		refused(file(header(64, 32, 8, 6, 0), type("IDAT"), new byte[] {1, 2, 3, 4, 5}), "corrupt");
		refused(file(header(64, 32, 8, 6, 0), type("ABCD"), new byte[0], type("IDAT"), deflate(new byte[32 * 257])), "critical chunk");
		byte[] badFilter = new byte[32 * 257];
		badFilter[0] = 9;
		refused(file(header(64, 32, 8, 6, 0), type("IDAT"), deflate(badFilter)), "row filter");
		refused(file(header(64, 32, 8, 6, 0)), "no image data");
	}

	@Test
	void aHugeHeaderIsRefusedBeforeAnythingIsInflated() {
		// 30000×30000 RGBA would be 3.6 GB once inflated: refused from its header alone.
		refused(file(header(30000, 30000, 8, 6, 0), type("IDAT"), deflate(new byte[100])), "over the limit");
		refused(file(header(2048, 1024, 8, 6, 0), type("IDAT"), deflate(new byte[100])), "over the limit");
	}

	@Test
	void imageDataLargerThanItsHeaderIsAZipBombAndRefused() {
		// A megabyte of zeros (it compresses to a few kilobytes) behind a 64×32 header.
		refused(file(header(64, 32, 8, 6, 0), type("IDAT"), deflate(new byte[1024 * 1024])), "larger than its size");
	}
}
