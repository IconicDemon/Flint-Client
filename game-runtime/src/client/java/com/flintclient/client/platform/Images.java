package com.flintclient.client.platform;

import com.flintclient.client.render.Pixels;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Reads Flint's pictures from the game's resources (so a resource pack may replace them), as ARGB pixels. Safe on a worker
 * thread (a resource read and a PNG decode, nothing on the GPU). Platform class (see {@link Clock}).
 */
public final class Images {
	private Images() {
	}

	/** The PNG at {@code flintclient:<path>}, or null when there is none. */
	public static @Nullable Pixels read(String path) throws IOException {
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(Texture.id(path));
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth();
			int h = image.getHeight();
			int[] argb = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					argb[y * w + x] = image.getPixel(x, y);
				}
			}
			return new Pixels(w, h, argb);
		}
	}

	/**
	 * A PNG or JPEG picture's pixels (Flint's main menu art from the Flint app), or null when it isn't one, is larger than
	 * {@link com.flintclient.client.render.PictureHeader#MAX_SIDE} either way, or can't be read. Its size is read from its
	 * header before anything is decoded. Worker thread.
	 */
	public static @Nullable Pixels decode(byte[] bytes) {
		try {
			if (com.flintclient.client.render.PictureHeader.pngSize(bytes) != null) {
				// From a stream, which Minecraft reads into a native buffer of the file's size: 1.21.1's read(byte[]) copies the
				// whole file onto the thread's small native stack, so a picture of more than 64 KB threw OutOfMemoryError
				// ("Out of stack space") and killed the menu feeds' worker (found by the crash sweep).
				try (NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(bytes))) {
					int w = image.getWidth();
					int h = image.getHeight();
					int[] argb = new int[w * h];
					for (int y = 0; y < h; y++) {
						for (int x = 0; x < w; x++) {
							argb[y * w + x] = image.getPixel(x, y);
						}
					}
					return new Pixels(w, h, argb);
				}
			}
			return com.flintclient.client.render.PictureHeader.readJpeg(bytes);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
