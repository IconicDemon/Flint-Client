package com.flintclient.legacy.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import org.lwjgl.opengl.GL11;

/**
 * Flint's drawing on 1.8.9, in window pixels from the top left: plain and gradient rectangles, antialiased rounded
 * rectangles and rings (corner masks from the {@link Atlas}), glyphs, textures and Minecraft's own text at whole-pixel
 * scales. Shapes from the atlas are queued as one batch of quads and drawn when something else (text, a picture) needs the
 * GPU, or at {@link #end}. {@link #begin} sets up a pixel projection over whatever is on screen and {@link #end} restores
 * Minecraft's. Render thread.
 */
public final class Gfx {
	private static boolean batching;
	private static boolean active;
	/** An offset every shape is moved by (a page rising as it fades in), and the opacity it is drawn at. */
	private static float offsetX;
	private static float offsetY;
	private static float opacity = 1;
	private static int scissorDepth;
	private static final int[] SCISSOR = new int[4 * 8];

	private Gfx() {
	}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Window width and height in pixels. */
	public static int width() {
		return mc().width;
	}

	public static int height() {
		return mc().height;
	}

	/** Starts drawing in window pixels. */
	public static void begin() {
		Minecraft mc = mc();
		GlStateManager.matrixMode(GL11.GL_PROJECTION);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		GlStateManager.ortho(0, mc.width, mc.height, 0, 1000, 3000);
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.pushMatrix();
		GlStateManager.loadIdentity();
		GlStateManager.translatef(0, 0, -2000);
		GlStateManager.disableLighting();
		GlStateManager.disableDepthTest();
		GlStateManager.disableAlphaTest();
		GlStateManager.disableCull();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		GlStateManager.color4f(1, 1, 1, 1);
		active = true;
		offsetX = 0;
		offsetY = 0;
		opacity = 1;
	}

	/** Draws what is queued and gives Minecraft its projection and state back. */
	public static void end() {
		flush();
		while (scissorDepth > 0) {
			popClip();
		}
		active = false;
		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.enableAlphaTest();
		GlStateManager.enableDepthTest();
		GlStateManager.color4f(1, 1, 1, 1);
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.matrixMode(GL11.GL_PROJECTION);
		GlStateManager.popMatrix();
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.popMatrix();
	}

	public static boolean active() {
		return active;
	}

	// ---------- the batch ----------

	private static BufferBuilder batch() {
		BufferBuilder b = Tesselator.getInstance().getBuffer();
		if (!batching) {
			b.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
			batching = true;
		}
		return b;
	}

	/** Draws the queued shapes now (before text or a texture of another kind). */
	public static void flush() {
		if (!batching) {
			return;
		}
		batching = false;
		GlStateManager.enableTexture();
		GlStateManager.bindTexture(Atlas.get().texture());
		GlStateManager.color4f(1, 1, 1, 1);
		Tesselator.getInstance().end();
	}

	// ---------- state ----------

	public static void setOffset(float x, float y) {
		offsetX = x;
		offsetY = y;
	}

	public static float offsetX() {
		return offsetX;
	}

	public static float offsetY() {
		return offsetY;
	}

	public static float opacity() {
		return opacity;
	}

	public static void setOpacity(float value) {
		opacity = Math.max(0, Math.min(1, value));
	}

	/** Limits drawing to a rectangle (window pixels), inside any clip already set. */
	public static void pushClip(int x, int y, int w, int h) {
		flush();
		int x0 = x;
		int y0 = y;
		int x1 = x + w;
		int y1 = y + h;
		if (scissorDepth > 0) {
			int p = (scissorDepth - 1) * 4;
			x0 = Math.max(x0, SCISSOR[p]);
			y0 = Math.max(y0, SCISSOR[p + 1]);
			x1 = Math.min(x1, SCISSOR[p] + SCISSOR[p + 2]);
			y1 = Math.min(y1, SCISSOR[p + 1] + SCISSOR[p + 3]);
		}
		int p = scissorDepth * 4;
		SCISSOR[p] = x0;
		SCISSOR[p + 1] = y0;
		SCISSOR[p + 2] = Math.max(0, x1 - x0);
		SCISSOR[p + 3] = Math.max(0, y1 - y0);
		scissorDepth++;
		apply();
	}

	public static void popClip() {
		flush();
		scissorDepth = Math.max(0, scissorDepth - 1);
		apply();
	}

	private static void apply() {
		if (scissorDepth == 0) {
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			return;
		}
		int p = (scissorDepth - 1) * 4;
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(SCISSOR[p], height() - SCISSOR[p + 1] - SCISSOR[p + 3], SCISSOR[p + 2], SCISSOR[p + 3]);
	}

	// ---------- colour ----------

	private static int alpha(int argb) {
		return Math.round((argb >>> 24) * opacity);
	}

	private static void vertex(BufferBuilder b, float x, float y, float u, float v, int argb) {
		b.vertex(x + offsetX, y + offsetY, 0).texture(u, v).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, alpha(argb)).nextVertex();
	}

	// ---------- shapes ----------

	/** A plain rectangle. */
	public static void rect(float x, float y, float w, float h, int argb) {
		gradient(x, y, w, h, argb, argb);
	}

	/** A rectangle running from {@code top} to {@code bottom}. */
	public static void gradient(float x, float y, float w, float h, int top, int bottom) {
		if (w <= 0 || h <= 0 || ((top | bottom) >>> 24) == 0) {
			return;
		}
		Atlas.Entry s = Atlas.get().solid();
		float u = (s.x + 2) / (float) Atlas.SIZE;
		float v = (s.y + 2) / (float) Atlas.SIZE;
		BufferBuilder b = batch();
		vertex(b, x, y, u, v, top);
		vertex(b, x, y + h, u, v, bottom);
		vertex(b, x + w, y + h, u, v, bottom);
		vertex(b, x + w, y, u, v, top);
	}

	/** A rectangle whose colour runs along a direction: {@code colors} at the corners (top left, bottom left, bottom right, top right). */
	public static void corners(float x, float y, float w, float h, int tl, int bl, int br, int tr) {
		Atlas.Entry s = Atlas.get().solid();
		float u = (s.x + 2) / (float) Atlas.SIZE;
		float v = (s.y + 2) / (float) Atlas.SIZE;
		BufferBuilder b = batch();
		vertex(b, x, y, u, v, tl);
		vertex(b, x, y + h, u, v, bl);
		vertex(b, x + w, y + h, u, v, br);
		vertex(b, x + w, y, u, v, tr);
	}

	/** A rounded rectangle in one colour. Coordinates are rounded to whole pixels. */
	public static void round(float x, float y, float w, float h, float radius, int argb) {
		roundGradient(x, y, w, h, radius, argb, argb);
	}

	/** A rounded rectangle running from {@code top} to {@code bottom}. */
	public static void roundGradient(float x, float y, float w, float h, float radius, int top, int bottom) {
		int x0 = Math.round(x);
		int y0 = Math.round(y);
		int pw = Math.round(x + w) - x0;
		int ph = Math.round(y + h) - y0;
		int r = Math.min(Math.round(radius), Math.min(pw, ph) / 2);
		if (pw <= 0 || ph <= 0 || ((top | bottom) >>> 24) == 0) {
			return;
		}
		if (r <= 0) {
			gradient(x0, y0, pw, ph, top, bottom);
			return;
		}
		int mid1 = lerpColor(top, bottom, r / (float) ph);
		int mid2 = lerpColor(top, bottom, (ph - r) / (float) ph);
		Atlas.Entry c = Atlas.get().corner(r, 0);
		cornerQuads(c, x0, y0, pw, ph, r, top, mid1, mid2, bottom);
		gradient(x0 + r, y0, pw - 2 * r, r, top, mid1);
		gradient(x0, y0 + r, pw, ph - 2 * r, mid1, mid2);
		gradient(x0 + r, y0 + ph - r, pw - 2 * r, r, mid2, bottom);
	}

	/** A ring {@code t} pixels thick just inside a rounded rectangle's edge, in one colour. */
	public static void ring(float x, float y, float w, float h, float radius, int t, int argb) {
		ringGradient(x, y, w, h, radius, t, argb, argb);
	}

	/** A ring {@code t} pixels thick, its colour running from {@code top} to {@code bottom}. */
	public static void ringGradient(float x, float y, float w, float h, float radius, int t, int top, int bottom) {
		int x0 = Math.round(x);
		int y0 = Math.round(y);
		int pw = Math.round(x + w) - x0;
		int ph = Math.round(y + h) - y0;
		if (pw <= 0 || ph <= 0 || t <= 0 || ((top | bottom) >>> 24) == 0) {
			return;
		}
		int r = Math.min(Math.round(radius), Math.min(pw, ph) / 2);
		if (r < t) {
			r = 0;
		}
		int mid1 = lerpColor(top, bottom, Math.max(r, t) / (float) ph);
		int mid2 = lerpColor(top, bottom, (ph - Math.max(r, t)) / (float) ph);
		if (r > 0) {
			cornerQuads(Atlas.get().corner(r, t), x0, y0, pw, ph, r, top, mid1, mid2, bottom);
		}
		int edge = Math.max(r, t);
		// Top and bottom edges between the corners, then the sides between them.
		gradient(x0 + r, y0, pw - 2 * r, t, top, lerpColor(top, bottom, t / (float) ph));
		gradient(x0 + r, y0 + ph - t, pw - 2 * r, t, lerpColor(top, bottom, (ph - t) / (float) ph), bottom);
		if (r == 0) {
			gradient(x0, y0 + t, t, ph - 2 * t, mid1, mid2);
			gradient(x0 + pw - t, y0 + t, t, ph - 2 * t, mid1, mid2);
		} else {
			gradient(x0, y0 + edge, t, ph - 2 * edge, mid1, mid2);
			gradient(x0 + pw - t, y0 + edge, t, ph - 2 * edge, mid1, mid2);
		}
	}

	/** The four corners from one top-left mask, flipped for the others. */
	private static void cornerQuads(Atlas.Entry c, int x0, int y0, int pw, int ph, int r, int top, int mid1, int mid2, int bottom) {
		BufferBuilder b = batch();
		float u0 = c.u0();
		float v0 = c.v0();
		float u1 = c.u1();
		float v1 = c.v1();
		// top left
		vertex(b, x0, y0, u0, v0, top);
		vertex(b, x0, y0 + r, u0, v1, mid1);
		vertex(b, x0 + r, y0 + r, u1, v1, mid1);
		vertex(b, x0 + r, y0, u1, v0, top);
		// top right (flipped across)
		int xr = x0 + pw - r;
		vertex(b, xr, y0, u1, v0, top);
		vertex(b, xr, y0 + r, u1, v1, mid1);
		vertex(b, xr + r, y0 + r, u0, v1, mid1);
		vertex(b, xr + r, y0, u0, v0, top);
		// bottom left (flipped down)
		int yb = y0 + ph - r;
		vertex(b, x0, yb, u0, v1, mid2);
		vertex(b, x0, yb + r, u0, v0, bottom);
		vertex(b, x0 + r, yb + r, u1, v0, bottom);
		vertex(b, x0 + r, yb, u1, v1, mid2);
		// bottom right
		vertex(b, xr, yb, u1, v1, mid2);
		vertex(b, xr, yb + r, u1, v0, bottom);
		vertex(b, xr + r, yb + r, u0, v0, bottom);
		vertex(b, xr + r, yb, u0, v1, mid2);
	}

	/** An atlas entry drawn at {@code x, y} (whole pixels, its own size) in {@code argb}. */
	public static void entry(Atlas.Entry e, float x, float y, int argb) {
		entry(e, x, y, e.w, e.h, argb);
	}

	public static void entry(Atlas.Entry e, float x, float y, float w, float h, int argb) {
		BufferBuilder b = batch();
		int x0 = Math.round(x);
		int y0 = Math.round(y);
		vertex(b, x0, y0, e.u0(), e.v0(), argb);
		vertex(b, x0, y0 + h, e.u0(), e.v1(), argb);
		vertex(b, x0 + w, y0 + h, e.u1(), e.v1(), argb);
		vertex(b, x0 + w, y0, e.u1(), e.v0(), argb);
	}

	/** A whole texture (another GL texture) stretched over a rectangle, tinted. */
	public static void texture(int glTexture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
		flush();
		GlStateManager.enableTexture();
		GlStateManager.bindTexture(glTexture);
		BufferBuilder b = Tesselator.getInstance().getBuffer();
		b.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		vertex(b, x, y, u0, v0, argb);
		vertex(b, x, y + h, u0, v1, argb);
		vertex(b, x + w, y + h, u1, v1, argb);
		vertex(b, x + w, y, u1, v0, argb);
		Tesselator.getInstance().end();
	}

	// ---------- colour helpers ----------

	public static int lerpColor(int a, int b, float t) {
		if (t <= 0) {
			return a;
		}
		if (t >= 1) {
			return b;
		}
		int aa = a >>> 24;
		int ar = (a >> 16) & 255;
		int ag = (a >> 8) & 255;
		int ab = a & 255;
		return (Math.round(aa + ((b >>> 24) - aa) * t) << 24) | (Math.round(ar + (((b >> 16) & 255) - ar) * t) << 16)
			| (Math.round(ag + (((b >> 8) & 255) - ag) * t) << 8) | Math.round(ab + ((b & 255) - ab) * t);
	}

	/** {@code rgb} at {@code alpha} (0..1); the colour's own alpha is replaced. */
	public static int withAlpha(int argb, float alpha) {
		return (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | (argb & 0xFFFFFF);
	}

	/** {@code argb} multiplied by an extra alpha. */
	public static int fade(int argb, float alpha) {
		return (Math.round((argb >>> 24) * Math.max(0, Math.min(1, alpha))) << 24) | (argb & 0xFFFFFF);
	}

	/** {@code top} composited over the opaque {@code bottom}. */
	public static int over(int top, int bottom) {
		float a = (top >>> 24) / 255.0F;
		return 0xFF000000 | (lerpColor(bottom, top | 0xFF000000, a) & 0xFFFFFF);
	}
}
