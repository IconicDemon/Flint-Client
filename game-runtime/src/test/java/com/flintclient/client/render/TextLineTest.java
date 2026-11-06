package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.platform.TextEngine;
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
	void aFractionalScaleDrawsTheFaceRasterisedAtItsSizeInPixels() {
		FontFace exact = TextLine.atScale(FlintFonts.LABEL, 2.75F);
		assertEquals(FlintFonts.LABEL.size * 2.75F, exact.size(), 0.05F, "the same typeface at its size in pixels");
		assertEquals(1, exact.drawScale(1), "drawn one to one: as crisp as at a whole scale");
		assertEquals(FlintFonts.LABEL.capHeightPx(1) * 2.75F, TextLine.capHeightPx(FlintFonts.LABEL, 2.75F), 0.1F);
		assertEquals(FlintFonts.LABEL.capHeightPx(3), TextLine.capHeightPx(FlintFonts.LABEL, 3.0F), 0.001F, "a whole scale as before");
		assertTrue(TextLine.whole(3.0F) && !TextLine.whole(2.25F));

		CountingFont glyphs = new CountingFont();
		TextEngine engine = TextEngine.of(new Font(glyphs));
		TextLine line = new TextLine(FlintFonts.LABEL, "Flint");
		line.prepare(2.25F, engine);
		assertEquals(25, line.widthPx(), "5 glyphs of 5 pixels: the fake font's advance at one pixel per unit");
		line.prepare(2, engine);
		assertEquals(50, line.widthPx(), "a whole scale still multiplies");
	}

	@Test
	void textTheFaceCantShowIsDrawnInMinecraftsFontAtTheFacesSizeNotTheGuiScale() {
		CountingFont glyphs = new CountingFont();
		TextEngine engine = TextEngine.of(new Font(glyphs));
		// The 0.9.1 notes' language list: Cyrillic, Devanagari and CJK aren't in Nunito Sans.
		TextLine line = new TextLine(FlintFonts.LABEL, "Русский, हिन्दी, 日本語");
		line.prepare(3.6F, engine);
		assertTrue(line.fallback());
		float faceCap = TextLine.capHeightPx(FlintFonts.LABEL, 3.6F);
		assertEquals(2, TextLine.fallbackScale(faceCap), "LABEL's capitals are 11.7 px at 3.6: Minecraft's at 2x are 14 px, at 4x (the GUI scale) 28");
		assertEquals(14.0F, line.capHeightPx(3.6F), 0.001F);
		assertTrue(Math.abs(line.capHeightPx(3.6F) - faceCap) < TextLine.MC_CAP_UNITS / 2 + 0.001F, "within half a step of the face");
		assertEquals(1, TextLine.fallbackScale(2.0F), "never below one pixel per font pixel");
		TextLine latin = new TextLine(FlintFonts.LABEL, "English, Español");
		latin.prepare(3.6F, engine);
		assertFalse(latin.fallback());
	}

	@Test
	void measuringThenDrawingAtTheFittedWidthBuildsTheCopyOnce() {
		CountingFont glyphs = new CountingFont();
		TextEngine engine = TextEngine.of(new Font(glyphs));
		TextLine line = new TextLine(FlintFonts.LABEL, "Reset to defaults");
		// 17 characters, 5 units each, at 2 pixels per unit: 170 px, so 100 px needs an ellipsis.
		TextLine fitted = line.fitted(2, engine, 100);
		assertTrue(fitted.widthPx() <= 100);
		assertTrue(fitted.text().endsWith("…"));
		int afterBuild = glyphs.lookups;
		assertSame(fitted, line.fitted(2, engine, 100), "the same width");
		assertSame(fitted, line.fitted(2, engine, fitted.widthPx()), "the width the first call returned (a button draws at it)");
		assertEquals(afterBuild, glyphs.lookups, "no measuring after the first build");
		String shown = fitted.text();
		line.fitted(2, engine, fitted.widthPx() - 1);
		assertTrue(glyphs.lookups > afterBuild, "a narrower width is a new copy");
		assertTrue(fitted.text().length() < shown.length());
		assertSame(line, line.fitted(2, engine, 170), "a line that fits is drawn whole");
	}
}
