package com.flintclient.client.control.feature;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A strict PNG decoder for cosmetic textures downloaded from the Store, so no untrusted image ever reaches a native decoder.
 * The header is read and checked against the caller's size limits before a byte of image data is inflated, and inflating
 * stops at exactly the size the header implies (a "zip bomb" fails at the first byte past it). Every chunk's CRC is checked;
 * chunks must come in PNG's order. Reads 8-bit greyscale, grey with alpha, RGB, RGBA and palette images (with {@code tRNS}),
 * not interlaced: what the Store writes (it re-encodes every texture as 8-bit RGBA, Creator src/lib/server/texture.ts) and
 * what image editors save. Anything else is refused with a reason. Pure Java; no AWT, no native code.
 */
public final class CapePng {
	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
	/** More chunks than any texture has: a file of thousands of empty chunks is refused rather than walked. */
	private static final int MAX_CHUNKS = 4096;

	/** A decoded image: {@code width × height} ARGB pixels, row by row. */
	public record Image(int width, int height, int[] argb) {
	}

	/** Why a file was refused. */
	public static final class Refused extends Exception {
		public Refused(String reason) {
			super(reason, null, false, false);
		}
	}

	private CapePng() {
	}

	/** Decodes {@code png}, refusing an image wider than {@code maxWidth} or taller than {@code maxHeight}. */
	public static Image decode(byte[] png, int maxWidth, int maxHeight) throws Refused {
		if (png.length < SIGNATURE.length + 12 + 13 + 12) {
			throw new Refused("too short to be a PNG");
		}
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (png[i] != SIGNATURE[i]) {
				throw new Refused("not a PNG");
			}
		}
		int at = SIGNATURE.length;
		int width = 0;
		int height = 0;
		int colour = -1;
		int channels = 0;
		int[] palette = null;
		byte[] paletteAlpha = null;
		int[] key = null;
		boolean ended = false;
		boolean seenData = false;
		boolean dataDone = false;
		Inflater inflater = null;
		byte[] raw = null;
		int rawFilled = 0;
		CRC32 crc = new CRC32();
		try {
			for (int chunks = 0; !ended; chunks++) {
				if (chunks == MAX_CHUNKS) {
					throw new Refused("too many chunks");
				}
				if (png.length - at < 12) {
					throw new Refused("truncated");
				}
				long length = u32(png, at);
				if (length > png.length - at - 12L) {
					throw new Refused("truncated chunk");
				}
				int len = (int) length;
				int typeAt = at + 4;
				int dataAt = at + 8;
				crc.reset();
				crc.update(png, typeAt, 4 + len);
				if (crc.getValue() != u32(png, dataAt + len)) {
					throw new Refused("chunk checksum mismatch");
				}
				String type = new String(png, typeAt, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
				if (chunks == 0 && !type.equals("IHDR")) {
					throw new Refused("no header first");
				}
				switch (type) {
					case "IHDR" -> {
						if (chunks != 0 || len != 13) {
							throw new Refused("bad header");
						}
						long w = u32(png, dataAt);
						long h = u32(png, dataAt + 4);
						int depth = png[dataAt + 8] & 0xFF;
						colour = png[dataAt + 9] & 0xFF;
						int compression = png[dataAt + 10] & 0xFF;
						int filter = png[dataAt + 11] & 0xFF;
						int interlace = png[dataAt + 12] & 0xFF;
						if (w < 1 || h < 1 || w > maxWidth || h > maxHeight) {
							throw new Refused("size " + w + "x" + h + " is over the limit");
						}
						if (compression != 0 || filter != 0) {
							throw new Refused("unknown compression or filter method");
						}
						if (interlace != 0) {
							throw new Refused("interlaced images aren't read");
						}
						if (depth != 8) {
							throw new Refused("only 8-bit images are read");
						}
						channels = switch (colour) {
							case 0 -> 1;
							case 2 -> 3;
							case 3 -> 1;
							case 4 -> 2;
							case 6 -> 4;
							default -> throw new Refused("unknown colour type " + colour);
						};
						width = (int) w;
						height = (int) h;
						raw = new byte[height * (1 + width * channels)];
						inflater = new Inflater();
					}
					case "PLTE" -> {
						if (seenData || palette != null || len % 3 != 0 || len == 0 || len > 768) {
							throw new Refused("bad palette");
						}
						palette = new int[len / 3];
						for (int i = 0; i < palette.length; i++) {
							int o = dataAt + i * 3;
							palette[i] = 0xFF000000 | (png[o] & 0xFF) << 16 | (png[o + 1] & 0xFF) << 8 | png[o + 2] & 0xFF;
						}
					}
					case "tRNS" -> {
						if (seenData) {
							throw new Refused("transparency after the image data");
						}
						if (colour == 3) {
							if (palette == null || len > palette.length) {
								throw new Refused("bad palette transparency");
							}
							paletteAlpha = java.util.Arrays.copyOfRange(png, dataAt, dataAt + len);
						} else if (colour == 0 && len == 2) {
							key = new int[] {u16(png, dataAt)};
						} else if (colour == 2 && len == 6) {
							key = new int[] {u16(png, dataAt), u16(png, dataAt + 2), u16(png, dataAt + 4)};
						} else {
							throw new Refused("bad transparency");
						}
					}
					case "IDAT" -> {
						if (dataDone) {
							throw new Refused("image data split by another chunk");
						}
						if (colour == 3 && palette == null) {
							throw new Refused("palette image without a palette");
						}
						seenData = true;
						inflater.setInput(png, dataAt, len);
						rawFilled = inflate(inflater, raw, rawFilled);
					}
					case "IEND" -> {
						if (!seenData) {
							throw new Refused("no image data");
						}
						ended = true;
					}
					default -> {
						// Ancillary chunks (lower-case first letter) are skipped; an unknown critical one is refused.
						if (Character.isUpperCase(type.charAt(0))) {
							throw new Refused("unknown critical chunk " + type);
						}
						if (seenData) {
							dataDone = true;
						}
					}
				}
				at = dataAt + len + 4;
			}
			if (rawFilled != raw.length || !inflater.finished()) {
				throw new Refused("image data is short or doesn't end");
			}
		} catch (DataFormatException e) {
			throw new Refused("image data is corrupt");
		} finally {
			if (inflater != null) {
				inflater.end();
			}
		}
		unfilter(raw, width, height, channels);
		return new Image(width, height, pixels(raw, width, height, colour, channels, palette, paletteAlpha, key));
	}

	/** Inflates what {@code inflater} has into {@code out} from {@code filled}; more than {@code out} holds is refused. */
	private static int inflate(Inflater inflater, byte[] out, int filled) throws DataFormatException, Refused {
		while (!inflater.needsInput() && !inflater.finished()) {
			if (filled == out.length) {
				// Room is full but the stream goes on: one byte more would be past what the header allows.
				byte[] probe = new byte[1];
				if (inflater.inflate(probe) > 0) {
					throw new Refused("image data is larger than its size");
				}
				if (inflater.needsDictionary()) {
					throw new Refused("image data needs a dictionary");
				}
				break;
			}
			int n = inflater.inflate(out, filled, out.length - filled);
			if (n == 0 && inflater.needsDictionary()) {
				throw new Refused("image data needs a dictionary");
			}
			filled += n;
		}
		return filled;
	}

	/** Undoes PNG's per-row filters in place (types 0–4). */
	private static void unfilter(byte[] raw, int width, int height, int bpp) throws Refused {
		int stride = width * bpp;
		for (int y = 0; y < height; y++) {
			int row = y * (stride + 1);
			int filter = raw[row] & 0xFF;
			int prev = row - (stride + 1);
			for (int i = 0; i < stride; i++) {
				int at = row + 1 + i;
				int a = i >= bpp ? raw[at - bpp] & 0xFF : 0;
				int b = y > 0 ? raw[prev + 1 + i] & 0xFF : 0;
				int c = y > 0 && i >= bpp ? raw[prev + 1 + i - bpp] & 0xFF : 0;
				int x = raw[at] & 0xFF;
				int value = switch (filter) {
					case 0 -> x;
					case 1 -> x + a;
					case 2 -> x + b;
					case 3 -> x + (a + b >>> 1);
					case 4 -> x + paeth(a, b, c);
					default -> throw new Refused("unknown row filter " + filter);
				};
				raw[at] = (byte) value;
			}
		}
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
	}

	private static int[] pixels(byte[] raw, int width, int height, int colour, int channels, int[] palette, byte[] paletteAlpha, int[] key)
		throws Refused {
		int[] argb = new int[width * height];
		int stride = width * channels;
		for (int y = 0; y < height; y++) {
			int row = y * (stride + 1) + 1;
			for (int x = 0; x < width; x++) {
				int o = row + x * channels;
				int p;
				switch (colour) {
					case 0 -> {
						int g = raw[o] & 0xFF;
						int alpha = key != null && g == key[0] ? 0 : 0xFF;
						p = alpha << 24 | g << 16 | g << 8 | g;
					}
					case 2 -> {
						int r = raw[o] & 0xFF;
						int g = raw[o + 1] & 0xFF;
						int b = raw[o + 2] & 0xFF;
						int alpha = key != null && r == key[0] && g == key[1] && b == key[2] ? 0 : 0xFF;
						p = alpha << 24 | r << 16 | g << 8 | b;
					}
					case 3 -> {
						int index = raw[o] & 0xFF;
						if (index >= palette.length) {
							throw new Refused("palette index out of range");
						}
						int alpha = paletteAlpha != null && index < paletteAlpha.length ? paletteAlpha[index] & 0xFF : 0xFF;
						p = alpha << 24 | palette[index] & 0xFFFFFF;
					}
					case 4 -> {
						int g = raw[o] & 0xFF;
						p = (raw[o + 1] & 0xFF) << 24 | g << 16 | g << 8 | g;
					}
					default -> p = (raw[o + 3] & 0xFF) << 24 | (raw[o] & 0xFF) << 16 | (raw[o + 1] & 0xFF) << 8 | raw[o + 2] & 0xFF;
				}
				argb[y * width + x] = p;
			}
		}
		return argb;
	}

	private static long u32(byte[] b, int at) {
		return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFFL) << 16 | (b[at + 2] & 0xFFL) << 8 | b[at + 3] & 0xFFL;
	}

	private static int u16(byte[] b, int at) {
		return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
	}

	/** Test and tool use: an 8-bit RGBA PNG of {@code argb} (filter 0 on every row). */
	public static byte[] encodeRgba(int width, int height, int[] argb) {
		byte[] raw = new byte[height * (1 + width * 4)];
		for (int y = 0; y < height; y++) {
			int row = y * (1 + width * 4);
			for (int x = 0; x < width; x++) {
				int p = argb[y * width + x];
				int o = row + 1 + x * 4;
				raw[o] = (byte) (p >>> 16);
				raw[o + 1] = (byte) (p >>> 8);
				raw[o + 2] = (byte) p;
				raw[o + 3] = (byte) (p >>> 24);
			}
		}
		java.util.zip.Deflater deflater = new java.util.zip.Deflater();
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) {
			idat.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(SIGNATURE, 0, SIGNATURE.length);
		byte[] header = new byte[13];
		put32(header, 0, width);
		put32(header, 4, height);
		header[8] = 8;
		header[9] = 6;
		chunk(out, "IHDR", header);
		chunk(out, "IDAT", idat.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] head = new byte[8];
		put32(head, 0, data.length);
		byte[] t = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		System.arraycopy(t, 0, head, 4, 4);
		out.write(head, 0, 8);
		out.write(data, 0, data.length);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		byte[] sum = new byte[4];
		put32(sum, 0, (int) crc.getValue());
		out.write(sum, 0, 4);
	}

	private static void put32(byte[] b, int at, int v) {
		b[at] = (byte) (v >>> 24);
		b[at + 1] = (byte) (v >>> 16);
		b[at + 2] = (byte) (v >>> 8);
		b[at + 3] = (byte) v;
	}
}
