package com.flintclient.client.mc;

/** Newer Minecraft's texture manager as Flint's main menu uses it (see {@link Component}): Minecraft 1.8.9's. Render thread. */
public final class TextureManager {
	TextureManager() {
	}

	static net.minecraft.client.render.texture.TextureManager game() {
		return Minecraft.game().getTextureManager();
	}

	/** Keeps {@code texture} under {@code id} until it is released. */
	public void register(Identifier id, DynamicTexture texture) {
		game().register(id.id(), texture.texture);
	}

	/** Frees {@code id}'s texture. */
	public void release(Identifier id) {
		game().close(id.id());
	}
}
