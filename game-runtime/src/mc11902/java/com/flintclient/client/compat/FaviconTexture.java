package com.flintclient.client.compat;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Locale;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * 1.20's server icon texture ({@code net.minecraft.client.gui.screens.FaviconTexture}) on Minecraft 1.19.2, whose server list
 * registers a dynamic texture per server the same way: a 64x64 icon under a name made from the address.
 */
public final class FaviconTexture implements AutoCloseable {
	private final TextureManager textures;
	private final ResourceLocation id;
	private @Nullable DynamicTexture texture;

	private FaviconTexture(TextureManager textures, ResourceLocation id) {
		this.textures = textures;
		this.id = id;
	}

	public static FaviconTexture forServer(TextureManager textures, String address) {
		String name = address.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
		return new FaviconTexture(textures, new ResourceLocation("flintclient", "servers/" + name + "/icon"));
	}

	/** Takes {@code image} as the icon (a 64x64 one, as Minecraft accepts; another size is refused and closed). */
	public void upload(NativeImage image) {
		if (image.getWidth() != 64 || image.getHeight() != 64) {
			image.close();
			throw new IllegalArgumentException("a server icon must be 64x64");
		}
		if (texture == null) {
			texture = new DynamicTexture(image);
			textures.register(id, texture);
		} else {
			texture.setPixels(image);
			texture.upload();
		}
	}

	public ResourceLocation textureLocation() {
		return id;
	}

	@Override
	public void close() {
		if (texture != null) {
			textures.release(id);
			texture.close();
			texture = null;
		}
	}
}
