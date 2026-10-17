package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.font.GlyphInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

class TextLineTest {
	/** A font whose every glyph is 5 units wide, counting how often a width is looked up (each is measuring work). */
	private static final class CountingFont implements Font.Provider, GlyphSource, BakedGlyph {
		private final GlyphInfo info = GlyphInfo.simple(5);
		int lookups;

		@Override
		public GlyphSource glyphs(FontDescription font) {
			return this;
		}

		@Override
		public EffectGlyph effect() {
			throw new UnsupportedOperationException("measuring only");
		}

		@Override
		public BakedGlyph getGlyph(int codepoint) {
			lookups++;
			return this;
		}

		@Override
		public BakedGlyph getRandomGlyph(RandomSource random, int width) {
			return this;
		}

		@Override
		public GlyphInfo info() {
			return info;
		}

		@Override
		public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style, float boldOffset, float shadowOffset) {
			throw new UnsupportedOperationException("measuring only");
		}
	}

	@Test
	void cutPointsAreCharacterBoundariesShorterThanTheText() {
		assertArrayEquals(new int[] {0, 1, 2, 3}, TextLine.cutPoints("abcd"));
		assertArrayEquals(new int[] {0}, TextLine.cutPoints("a"));
		assertArrayEquals(new int[] {0}, TextLine.cutPoints(""));
		// A surrogate pair (one emoji) is never split.
		assertArrayEquals(new int[] {0, 1, 3}, TextLine.cutPoints("a😀b"));
	}

	@Test
	void aShortenedCopyServesEveryWidthItStillFits() {
		assertTrue(TextLine.reusesFit(40, 44, 44), "the width it was built for");
		assertTrue(TextLine.reusesFit(40, 44, 40), "drawn at the width measuring returned");
		assertTrue(TextLine.reusesFit(40, 44, 42));
		assertFalse(TextLine.reusesFit(40, 44, 39), "narrower than the copy: a shorter one is needed");
		assertFalse(TextLine.reusesFit(40, 44, 45), "wider: a longer prefix may fit");
		assertTrue(TextLine.reusesFit(5, 3, 3), "an ellipsis that never fits is kept for its own width");
	}

	@Test
	void measuringThenDrawingAtTheFittedWidthBuildsTheCopyOnce() {
		CountingFont glyphs = new CountingFont();
		Font font = new Font(glyphs);
		TextLine line = new TextLine(FlintFonts.LABEL, "Reset to defaults");
		// 17 characters, 5 units each, at 2 pixels per unit: 170 px, so 100 px needs an ellipsis.
		TextLine fitted = line.fitted(2, font, 100);
		assertTrue(fitted.widthPx() <= 100);
		assertTrue(fitted.text().endsWith("…"));
		int afterBuild = glyphs.lookups;
		assertSame(fitted, line.fitted(2, font, 100), "the same width");
		assertSame(fitted, line.fitted(2, font, fitted.widthPx()), "the width the first call returned (a button draws at it)");
		assertEquals(afterBuild, glyphs.lookups, "no measuring after the first build");
		String shown = fitted.text();
		line.fitted(2, font, fitted.widthPx() - 1);
		assertTrue(glyphs.lookups > afterBuild, "a narrower width is a new copy");
		assertTrue(fitted.text().length() < shown.length());
		assertSame(line, line.fitted(2, font, 170), "a line that fits is drawn whole");
	}
}
