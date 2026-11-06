package com.flintclient.client.platform;

import com.flintclient.client.render.Pixels;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.texture.TextureUtil;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Reads Flint's pictures as ARGB pixels, on Minecraft 1.8.9: from the game's resources (a resource pack may replace them), else
 * from Flint's jar. Safe on a worker thread (a resource read and a PNG decode, nothing on the GPU). Platform class (see
 * {@link Clock}).
 */
public final class Images {
	private Images() {
	}

	/** The PNG at {@code flintclient:<path>}, or null when there is none. */
	public static @Nullable Pixels read(String path) throws IOException {
		InputStream in;
		try {
			in = Minecraft.getInstance().getResourceManager().getResource(new Identifier("flintclient", path)).asStream();
		} catch (FileNotFoundException notInPacks) {
			// 1.8.9 has no resource pack for mods: Flint's own file, from its jar.
			in = Images.class.getResourceAsStream("/assets/flintclient/" + path);
			if (in == null) {
				return null;
			}
		}
		try (InputStream stream = in) {
			BufferedImage image = TextureUtil.readImage(stream);
			int w = image.getWidth();
			int h = image.getHeight();
			return new Pixels(w, h, image.getRGB(0, 0, w, h, null, 0, w));
		}
	}
}
