package com.flintclient.client.compat;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** A texture's image decoded ahead of time (1.21.3's {@code TextureContents}; 1.21.1 has none). */
public final class TextureContents implements AutoCloseable {
	private final NativeImage image;
	private boolean taken;

	private TextureContents(NativeImage image) {
		this.image = image;
	}

	/** Reads and decodes {@code id} (any thread). */
	public static TextureContents load(ResourceManager resources, ResourceLocation id) throws IOException {
		try (InputStream in = resources.open(id)) {
			return new TextureContents(NativeImage.read(in));
		}
	}

	/** A texture made from the image, which it then owns (render thread). */
	public DynamicTexture texture() {
		taken = true;
		return new DynamicTexture(image);
	}

	@Override
	public void close() {
		if (!taken) {
			image.close();
		}
	}
}
