package com.flintclient.client.render;

import com.flintclient.client.platform.FontRef;
import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft's own font standing in for one of Flint's sizes (the menu's "Minecraft" font). It is a bitmap, so it is drawn
 * at a whole number of screen pixels per font pixel: the one whose capitals come closest to the Flint size's, never under
 * one, or a scale fixed for a whole screen ({@link #fixed}). It has every character Minecraft can show. Render thread.
 */
public final class PixelFace implements FontFace {
	/** Minecraft's capitals are 7 font pixels tall. */
	private static final float CAP_PIXELS = 7.0F;
	/** The cap height (fraction of the em) of the Flint face it stands in for. */
	private static final float FLINT_CAP = 0.705F;
	private static final Map<Integer, PixelFace> FACES = new HashMap<>();
	private static final Map<Integer, PixelFace> FIXED = new HashMap<>();
	/** Off. */
	public static final int OFF = -1;
	/** Every face in Minecraft's font, each at the scale nearest its own size. */
	public static final int BY_SIZE = 0;
	/**
	 * Whether every face is drawn as Minecraft's font (the Right Shift menu's screens): {@link #OFF}, {@link #BY_SIZE}, or
	 * a pixel scale every face is drawn at. Render thread.
	 */
	private static int everywhere = OFF;

	private final float size;
	/** Screen pixels per font pixel whatever the size, or 0 for the scale nearest {@link #size}. */
	private final int fixedScale;

	private PixelFace(float size, int fixedScale) {
		this.size = size;
		this.fixedScale = fixedScale;
	}

	/** Draws every face in Minecraft's font ({@code mode}: {@link #BY_SIZE} or a pixel scale) until {@link #end}; returns the previous mode for it. */
	public static int begin(int mode) {
		int was = everywhere;
		everywhere = mode;
		return was;
	}

	public static void end(int was) {
		everywhere = was;
	}

	/** The mode faces are drawn in now ({@link #OFF} when each is its own). */
	public static int everywhere() {
		return everywhere;
	}

	/** The face to draw {@code face} with now: itself, or Minecraft's font while {@link #everywhere()} is on. */
	public static FontFace shown(FontFace face) {
		if (everywhere == OFF || face instanceof PixelFace) {
			return face;
		}
		return everywhere == BY_SIZE ? of(face.size()) : fixed(everywhere);
	}

	/** Minecraft's font where Flint's would be {@code size} units (shared per size). */
	public static PixelFace of(float size) {
		return FACES.computeIfAbsent(Math.round(size * 20), s -> new PixelFace(s / 20.0F, 0));
	}

	/** Minecraft's font at {@code pixelScale} screen pixels per font pixel, whatever the canvas scale (shared per scale). */
	public static PixelFace fixed(int pixelScale) {
		int s = Math.max(1, pixelScale);
		return FIXED.computeIfAbsent(s, k -> new PixelFace(k * CAP_PIXELS / FLINT_CAP, k));
	}

	/** The pixel scale this face is fixed at, or 0 when it follows its size. */
	int fixedScale() {
		return fixedScale;
	}

	@Override
	public FontRef forScale(int scale) {
		return FontRef.MINECRAFT;
	}

	@Override
	public float size() {
		return size;
	}

	@Override
	public int drawScale(int scale) {
		if (fixedScale > 0) {
			return fixedScale;
		}
		// Rounded up from a little past a half: Minecraft's letters are wider, but at 1x they read as too small beside Flint's.
		return Math.max(1, Math.round(size * FLINT_CAP * scale / CAP_PIXELS + 0.35F));
	}

	@Override
	public float capHeightPx(int scale) {
		return CAP_PIXELS * drawScale(scale);
	}

	@Override
	public boolean covers(String text) {
		return true;
	}

	@Override
	public String toString() {
		return fixedScale > 0 ? "minecraft:default x" + fixedScale : "minecraft:default@" + size;
	}
}
