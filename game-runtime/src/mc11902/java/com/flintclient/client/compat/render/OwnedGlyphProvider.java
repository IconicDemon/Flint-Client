package com.flintclient.client.compat.render;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jspecify.annotations.Nullable;

/**
 * A glyph provider of a Flint font set on Minecraft 1.19.2, closed at most once. 1.19.2's font set closes only the providers
 * it kept (those that give it at least one glyph), and FontManager keeps no list of the rest (1.20's providersToClose): Flint
 * closes all of its own after a reload or at shutdown (FontManagerMixin), and a provider the set closed already stays closed.
 */
public final class OwnedGlyphProvider implements GlyphProvider {
	private final GlyphProvider provider;
	private boolean closed;

	public OwnedGlyphProvider(GlyphProvider provider) {
		this.provider = provider;
	}

	@Override
	public @Nullable GlyphInfo getGlyph(int codepoint) {
		return provider.getGlyph(codepoint);
	}

	@Override
	public IntSet getSupportedGlyphs() {
		return provider.getSupportedGlyphs();
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			provider.close();
		}
	}
}
