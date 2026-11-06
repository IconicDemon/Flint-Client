package com.flintclient.legacy.ui;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;

/**
 * Minecraft's own font as Flint's menu draws it: never with a shadow, always at a whole number of window pixels per font
 * pixel (it is a bitmap), the scale chosen so its capitals come closest to the size the design asks for. Widths are cached
 * per text (the menu's words don't change from frame to frame). Render thread.
 */
public final class Type {
	/** Minecraft's capitals are 7 font pixels tall. */
	public static final int CAP = 7;
	private static final Map<String, Integer> WIDTHS = new HashMap<String, Integer>();
	private static final Map<String, String> FITTED = new HashMap<String, String>();
	private static Object fontFor;

	private Type() {
	}

	private static TextRenderer font() {
		TextRenderer font = Gfx.mc().textRenderer;
		if (font != fontFor) {
			// A resource reload made a new font (or changed its glyph widths): measure again.
			fontFor = font;
			WIDTHS.clear();
			FITTED.clear();
		}
		return font;
	}

	/** Clears the cached widths (a resource pack changed the font). */
	public static void reload() {
		WIDTHS.clear();
		FITTED.clear();
	}

	/** Whole pixels per font pixel for text the design sets at {@code em} window pixels. */
	public static int scaleFor(float em) {
		return Math.max(1, Math.round(em * 0.705F / CAP + 0.35F));
	}

	/** Width in font pixels, without the space after the last letter. */
	public static int fontWidth(String text) {
		TextRenderer font = font();
		Integer w = WIDTHS.get(text);
		if (w == null) {
			w = Math.max(0, font.getWidth(text) - 1);
			if (WIDTHS.size() > 4096) {
				WIDTHS.clear();
			}
			WIDTHS.put(text, w);
		}
		return w;
	}

	/** Width in window pixels at {@code scale}. */
	public static int width(String text, int scale) {
		return fontWidth(text) * scale;
	}

	public static int height(int scale) {
		return CAP * scale;
	}

	/** Draws {@code text} from {@code x}, its capitals centred on {@code centerY}. */
	public static void draw(String text, float x, float centerY, int argb, int scale) {
		int alpha = Math.round((argb >>> 24) * Gfx.opacity());
		if (alpha < 6 || text.isEmpty()) {
			return;
		}
		Gfx.flush();
		int px = Math.round(x + Gfx.offsetX());
		int py = Math.round(centerY + Gfx.offsetY() - CAP * scale / 2.0F);
		GlStateManager.pushMatrix();
		GlStateManager.translatef(px, py, 0);
		GlStateManager.scalef(scale, scale, 1);
		GlStateManager.enableTexture();
		font().draw(text, 0, 0, (alpha << 24) | (argb & 0xFFFFFF), false);
		GlStateManager.popMatrix();
		GlStateManager.disableAlphaTest();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE,
			org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.color4f(1, 1, 1, 1);
	}

	/** Draws {@code text} centred on ({@code centerX}, {@code centerY}), shortened with "..." past {@code maxW}. */
	public static void centered(String text, float centerX, float centerY, int argb, int scale, float maxW) {
		String shown = fit(text, scale, maxW);
		draw(shown, centerX - width(shown, scale) / 2.0F, centerY, argb, scale);
	}

	/** Draws {@code text} from {@code x}, shortened with "..." past {@code maxW}. */
	public static void fitted(String text, float x, float centerY, int argb, int scale, float maxW) {
		draw(fit(text, scale, maxW), x, centerY, argb, scale);
	}

	/** {@code text}, or as much of it as fits {@code maxW} with "..." after it. */
	public static String fit(String text, int scale, float maxW) {
		int max = (int) Math.floor(maxW / scale);
		if (fontWidth(text) <= max) {
			return text;
		}
		String key = max + "\0" + text;
		String fitted = FITTED.get(key);
		if (fitted == null) {
			int dots = fontWidth("...") + 1;
			int end = text.length();
			while (end > 0 && fontWidth(text.substring(0, end).trim()) + dots > max) {
				end--;
			}
			fitted = end == 0 ? "" : text.substring(0, end).trim() + "...";
			if (FITTED.size() > 1024) {
				FITTED.clear();
			}
			FITTED.put(key, fitted);
		}
		return fitted;
	}
}
