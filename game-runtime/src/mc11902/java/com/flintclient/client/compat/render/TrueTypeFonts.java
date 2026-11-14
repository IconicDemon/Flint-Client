package com.flintclient.client.compat.render;

import com.flintclient.client.render.FlintFonts;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Function;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Flint's TrueType fonts on Minecraft 1.19.2, measured and placed as 1.20 and later do it (Flint's text layout is built on that).
 * 1.19.2's TrueType provider (STB) sizes a font so its ascent-to-descent height is the given size, puts the baseline at the
 * font's ascent and counts a glyph's left bearing twice; 1.20's (FreeType) sizes the em, puts the baseline
 * {@link FlintFonts#BASELINE} units below the text's position and places a glyph at its bitmap's left edge. So 1.19.2's provider
 * is handed the height the em makes, and each glyph's sheet is moved where 1.20 puts it ({@link Measured}): the same size and
 * place as on every other line, rasterised exactly as 1.19.2 rasterises (no sub-pixel shift).
 */
public final class TrueTypeFonts {
	private TrueTypeFonts() {
	}

	/** Flint's font {@code location} (under font/) at em size {@code size}, rasterised {@code oversample} times. */
	public static GlyphProvider load(ResourceManager resources, ResourceLocation location, float size, float oversample) throws IOException {
		STBTTFontinfo info = STBTTFontinfo.malloc();
		ByteBuffer bytes = null;
		try (InputStream in = resources.open(new ResourceLocation(location.getNamespace(), "font/" + location.getPath()))) {
			bytes = TextureUtil.readResource(in);
			bytes.flip();
			if (!STBTruetype.stbtt_InitFont(info, bytes)) {
				throw new IOException("Invalid ttf");
			}
			float perEm = STBTruetype.stbtt_ScaleForMappingEmToPixels(info, 1.0F);
			float perHeight = STBTruetype.stbtt_ScaleForPixelHeight(info, 1.0F);
			int ascent;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer up = stack.mallocInt(1);
				IntBuffer under = stack.mallocInt(1);
				IntBuffer gap = stack.mallocInt(1);
				STBTruetype.stbtt_GetFontVMetrics(info, up, under, gap);
				ascent = up.get(0);
			}
			// 1.19.2 scales the font's height to what it is handed, so it is handed the height the em makes at `size`.
			float height = size * perEm / perHeight;
			// Its baseline then sits at the ascent (size * ascent / em), and 1.19.2 draws every glyph 3 units above where its sheet
			// says (BakedGlyph: up - 3, for its bitmap fonts' bearing of 3); 1.20's baseline is at FlintFonts.BASELINE.
			float down = FlintFonts.BASELINE + 3.0F - size * ascent * perEm;
			float pixelsPerUnit = STBTruetype.stbtt_ScaleForPixelHeight(info, height * oversample);
			return new Measured(new TrueTypeGlyphProvider(bytes, info, height, oversample, 0.0F, 0.0F, ""), info, pixelsPerUnit / oversample, down);
		} catch (IOException | RuntimeException e) {
			info.free();
			MemoryUtil.memFree(bytes);
			throw e instanceof IOException io ? io : new IOException("Couldn't load " + location, e);
		}
	}

	/**
	 * 1.19.2's provider with each glyph placed as 1.20 places it: moved {@code down} GUI units, and left by its left bearing
	 * (which 1.19.2 adds to a bitmap box that already includes it).
	 */
	private static final class Measured implements GlyphProvider {
		private final GlyphProvider provider;
		private final STBTTFontinfo info;
		/** GUI units per font unit. */
		private final float unitsPerFontUnit;
		private final float down;

		Measured(GlyphProvider provider, STBTTFontinfo info, float unitsPerFontUnit, float down) {
			this.provider = provider;
			this.info = info;
			this.unitsPerFontUnit = unitsPerFontUnit;
			this.down = down;
		}

		@Override
		public @Nullable GlyphInfo getGlyph(int codepoint) {
			GlyphInfo glyph = provider.getGlyph(codepoint);
			if (glyph == null) {
				return null;
			}
			float left;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer advance = stack.mallocInt(1);
				IntBuffer bearing = stack.mallocInt(1);
				STBTruetype.stbtt_GetCodepointHMetrics(info, codepoint, advance, bearing);
				left = -bearing.get(0) * unitsPerFontUnit;
			}
			return new Placed(glyph, left, down);
		}

		@Override
		public IntSet getSupportedGlyphs() {
			return provider.getSupportedGlyphs();
		}

		@Override
		public void close() {
			provider.close();
		}
	}

	/** A glyph whose sheet is moved by ({@code dx}, {@code dy}) GUI units. */
	private record Placed(GlyphInfo glyph, float dx, float dy) implements GlyphInfo {
		@Override
		public float getAdvance() {
			return glyph.getAdvance();
		}

		@Override
		public float getBoldOffset() {
			return glyph.getBoldOffset();
		}

		@Override
		public float getShadowOffset() {
			return glyph.getShadowOffset();
		}

		@Override
		public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> baker) {
			return glyph.bake(sheet -> baker.apply(new Sheet(sheet, dx, dy)));
		}
	}

	private record Sheet(SheetGlyphInfo sheet, float dx, float dy) implements SheetGlyphInfo {
		@Override
		public int getPixelWidth() {
			return sheet.getPixelWidth();
		}

		@Override
		public int getPixelHeight() {
			return sheet.getPixelHeight();
		}

		@Override
		public void upload(int x, int y) {
			sheet.upload(x, y);
		}

		@Override
		public boolean isColored() {
			return sheet.isColored();
		}

		@Override
		public float getOversample() {
			return sheet.getOversample();
		}

		@Override
		public float getBearingX() {
			return sheet.getBearingX() + dx;
		}

		@Override
		public float getBearingY() {
			return sheet.getBearingY() + dy;
		}
	}
}
