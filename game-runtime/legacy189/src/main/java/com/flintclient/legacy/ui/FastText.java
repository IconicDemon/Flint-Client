package com.flintclient.legacy.ui;

import com.flintclient.legacy.mixin.TextRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import org.lwjgl.opengl.GL11;

/**
 * Minecraft's font drawn the way the HUD needs it, fast: a whole line (and its shadow) in one batch of quads instead of 1.8.9's
 * one immediate-mode strip per letter, with nothing allocated. The same glyphs, widths, colour codes and shadow as Minecraft's
 * own renderer (read from it, so resource packs' fonts apply). Text it can't draw this way (a letter outside the default font
 * page, a style code such as bold, the Unicode font) is handed to Minecraft's renderer unchanged. Render thread.
 */
public final class FastText {
	private static final String CHARS = "ÀÁÂÈÊËÍÓÔÕÚßãõğİıŒœŞşŴŵžȇ\u0000\u0000\u0000\u0000\u0000\u0000\u0000 !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜø£Ø×ƒáíóúñÑªº¿®¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αβΓπΣσμτΦΘΩδ∞∅∈∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u0000";
	/** A character's glyph on the default page, or -1. */
	private static final short[] INDEX = new short[65536];
	private static final String CODES = "0123456789abcdef";

	static {
		java.util.Arrays.fill(INDEX, (short) -1);
		for (int i = 0; i < CHARS.length(); i++) {
			char c = CHARS.charAt(i);
			if (c != 0 && INDEX[c] == -1) {
				INDEX[c] = (short) i;
			}
		}
	}

	private FastText() {
	}

	/** Whether {@code text} can be drawn here (every letter on the default page, only colour codes and resets). */
	private static boolean simple(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§') {
				if (i + 1 >= text.length()) {
					return false;
				}
				char code = Character.toLowerCase(text.charAt(++i));
				if (CODES.indexOf(code) < 0 && code != 'r') {
					return false;
				}
			} else if (c != ' ' && INDEX[c] < 0) {
				return false;
			}
		}
		return true;
	}

	/** Draws {@code text} at ({@code x}, {@code y}) as Minecraft's {@code TextRenderer.draw(text, x, y, color, shadow)} would. */
	public static void draw(String text, float x, float y, int color, boolean shadow) {
		Minecraft mc = Minecraft.getInstance();
		TextRenderer font = mc.textRenderer;
		if (text.isEmpty()) {
			return;
		}
		if (font.getUnicode() || !simple(text)) {
			font.draw(text, x, y, color, shadow);
			return;
		}
		if ((color & 0xFC000000) == 0) {
			color |= 0xFF000000;
		}
		TextRendererAccessor a = (TextRendererAccessor) font;
		int[] widths = a.flint$widths();
		int[] colors = a.flint$colors();
		GlStateManager.enableTexture();
		GlStateManager.enableAlphaTest();
		GlStateManager.color4f(1, 1, 1, 1);
		mc.getTextureManager().bind(a.flint$texture());
		Tesselator t = Tesselator.getInstance();
		BufferBuilder b = t.getBuffer();
		b.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		if (shadow) {
			int shade = (color & 0xFCFCFC) >> 2 | (color & 0xFF000000);
			layer(b, text, x + 1, y + 1, shade, true, widths, colors);
		}
		layer(b, text, x, y, color, false, widths, colors);
		t.end();
	}

	private static void layer(BufferBuilder b, String text, float x, float y, int color, boolean shadow, int[] widths, int[] colors) {
		int alpha = color >>> 24;
		int rgb = color & 0xFFFFFF;
		float at = x;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§') {
				char code = Character.toLowerCase(text.charAt(++i));
				int j = CODES.indexOf(code);
				rgb = j >= 0 ? colors[j + (shadow ? 16 : 0)] & 0xFFFFFF : color & 0xFFFFFF;
				continue;
			}
			if (c == ' ') {
				at += 4;
				continue;
			}
			int index = INDEX[c];
			int u = index % 16 * 8;
			int v = index / 16 * 8;
			int w = widths[index];
			float f = w - 0.01F;
			float u0 = u / 128F;
			float v0 = v / 128F;
			float u1 = (u + f - 1) / 128F;
			float v1 = (v + 7.99F) / 128F;
			int r = (rgb >> 16) & 255;
			int g = (rgb >> 8) & 255;
			int bl = rgb & 255;
			b.vertex(at, y, 0).texture(u0, v0).color(r, g, bl, alpha).nextVertex();
			b.vertex(at, y + 7.99F, 0).texture(u0, v1).color(r, g, bl, alpha).nextVertex();
			b.vertex(at + f - 1, y + 7.99F, 0).texture(u1, v1).color(r, g, bl, alpha).nextVertex();
			b.vertex(at + f - 1, y, 0).texture(u1, v0).color(r, g, bl, alpha).nextVertex();
			at += w;
		}
	}
}
