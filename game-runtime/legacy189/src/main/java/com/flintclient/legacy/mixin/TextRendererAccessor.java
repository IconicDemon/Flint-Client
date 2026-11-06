package com.flintclient.legacy.mixin;

import net.minecraft.client.render.TextRenderer;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The font's glyph widths, its texture and its colour codes, for Flint's batched HUD text. */
@Mixin(TextRenderer.class)
public interface TextRendererAccessor {
	@Accessor("characterWidths")
	int[] flint$widths();

	@Accessor("fontLocation")
	Identifier flint$texture();

	@Accessor("colors")
	int[] flint$colors();
}
