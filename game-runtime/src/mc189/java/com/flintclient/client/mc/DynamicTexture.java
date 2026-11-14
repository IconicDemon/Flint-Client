package com.flintclient.client.mc;

import java.util.function.Supplier;

/** A texture made from a picture in memory, as newer Minecraft names it: Minecraft 1.8.9's (see {@link Component}). Render thread. */
public final class DynamicTexture {
	final net.minecraft.client.render.texture.DynamicTexture texture;

	public DynamicTexture(Supplier<String> label, NativeImage image) {
		this.texture = new net.minecraft.client.render.texture.DynamicTexture(image.image());
	}
}
