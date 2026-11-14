package com.flintclient.client.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Checks a picture from the Flint app before it is decoded (main menu art: partner banners, showcase slides): what it is by
 * its first bytes, and its size from its header, so a picture that claims to be huge is refused before any pixel is made. JPEG
 * is decoded here with Java's own reader (Minecraft's reads PNG only). Platform-free.
 */
public final class PictureHeader {
	/** The most pixels a picture may have across or down. */
	public static final int MAX_SIDE = 4096;

	private PictureHeader() {
	}

	/** A PNG's width and height from its IHDR chunk, or null when it isn't a PNG or is larger than {@link #MAX_SIDE}. */
	public static int @Nullable [] pngSize(byte[] bytes) {
		if (bytes.length < 24 || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G' || bytes[12] != 'I'
			|| bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
			return null;
		}
		long w = readInt(bytes, 16);
		long h = readInt(bytes, 20);
		return w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE ? null : new int[] {(int) w, (int) h};
	}

	public static boolean jpeg(byte[] bytes) {
		return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
	}

	/** A JPEG's pixels as ARGB, or null when it isn't one, is larger than {@link #MAX_SIDE} either way, or can't be read. */
	public static @Nullable Pixels readJpeg(byte[] bytes) throws IOException {
		if (!jpeg(bytes)) {
			return null;
		}
		ImageIO.setUseCache(false);
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (in == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0);
				int h = reader.getHeight(0);
				if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) {
					return null;
				}
				BufferedImage image = reader.read(0);
				int[] argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
				return new Pixels(image.getWidth(), image.getHeight(), argb);
			} finally {
				reader.dispose();
			}
		}
	}

	private static long readInt(byte[] bytes, int at) {
		return (bytes[at] & 0xFFL) << 24 | (bytes[at + 1] & 0xFFL) << 16 | (bytes[at + 2] & 0xFFL) << 8 | bytes[at + 3] & 0xFFL;
	}
}
