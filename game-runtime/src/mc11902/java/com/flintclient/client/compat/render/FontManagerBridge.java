package com.flintclient.client.compat.render;

import net.minecraft.resources.ResourceLocation;

/**
 * Minecraft 1.19.2's FontManager as its reload listener (an anonymous class, mixin.FontManagerReloadMixin) asks it; added by
 * mixin.FontManagerMixin.
 */
public interface FontManagerBridge {
	/** Whether a font set by that name exists now. */
	boolean flint$hasFontSet(ResourceLocation id);

	/**
	 * After a reload dropped every font set: closes the glyph providers Flint's sets were made with (those a set still held
	 * were closed with it) and forgets which Flint fonts failed to load.
	 */
	void flint$reloaded();
}
