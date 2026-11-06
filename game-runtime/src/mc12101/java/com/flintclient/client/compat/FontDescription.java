package com.flintclient.client.compat;

import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * Which font a piece of text uses. 1.21.9 made it a {@code FontDescription}; 1.21.1 names a font by its id, as
 * {@link Resource} does. {@code Style.withFont} takes one through {@link Bridges.StyleFont}.
 */
public sealed interface FontDescription {
	Resource DEFAULT = new Resource(Style.DEFAULT_FONT);

	ResourceLocation id();

	record Resource(ResourceLocation id) implements FontDescription {
	}
}
