package com.flintclient.client.mc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * A picture in memory as newer Minecraft keeps one, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}): Java's
 * own, ARGB pixels as newer Minecraft's {@code getPixel} and {@code setPixel} take them.
 */
public final class NativeImage implements AutoCloseable {
	private final BufferedImage image;

	private NativeImage(BufferedImage image) {
		this.image = image;
	}

	/** A blank {@code width × height} picture (transparent). */
	public NativeImage(int width, int height, boolean zeroed) {
		this(new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB));
	}

	public static NativeImage read(byte[] png) throws IOException {
		return read(new ByteArrayInputStream(png));
	}

	public static NativeImage read(InputStream in) throws IOException {
		BufferedImage image = ImageIO.read(in);
		if (image == null) {
			throw new IOException("not a picture");
		}
		return new NativeImage(image);
	}

	public int getWidth() {
		return image.getWidth();
	}

	public int getHeight() {
		return image.getHeight();
	}

	public int getPixel(int x, int y) {
		return image.getRGB(x, y);
	}

	public void setPixel(int x, int y, int argb) {
		image.setRGB(x, y, argb);
	}

	BufferedImage image() {
		return image;
	}

	@Override
	public void close() {
	}
}
