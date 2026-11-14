package com.flintclient.client.mc;

import java.util.Locale;

/**
 * A saved server's icon as a texture, as newer Minecraft makes one for its server list, for Flint's main menu on Minecraft
 * 1.8.9 (see {@link Component}): a 1.8.9 dynamic texture under Flint's name for the server. Render thread.
 */
public final class FaviconTexture implements AutoCloseable {
	private final Identifier id;
	private boolean uploaded;

	private FaviconTexture(Identifier id) {
		this.id = id;
	}

	public static FaviconTexture forServer(TextureManager textures, String address) {
		String safe = address.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return new FaviconTexture(Identifier.fromNamespaceAndPath("flintclient", "servers/" + safe + "/" + Integer.toHexString(address.hashCode()) + "/icon"));
	}

	public void upload(NativeImage image) {
		net.minecraft.client.render.texture.DynamicTexture texture = new net.minecraft.client.render.texture.DynamicTexture(image.image());
		TextureManager.game().register(id.id(), texture);
		uploaded = true;
	}

	public Identifier textureLocation() {
		return id;
	}

	@Override
	public void close() {
		if (uploaded) {
			uploaded = false;
			TextureManager.game().close(id.id());
		}
	}
}
