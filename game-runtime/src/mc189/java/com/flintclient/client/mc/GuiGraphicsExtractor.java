package com.flintclient.client.mc;

import com.flintclient.client.platform.Frame;

/**
 * Newer Minecraft's GUI graphics as Flint's main menu uses them, on Minecraft 1.8.9 (see {@link Component}): everything goes
 * through Flint's {@link Frame} (window pixels, its own transforms and batches). One shared object; render thread.
 */
public final class GuiGraphicsExtractor {
	private static final GuiGraphicsExtractor SHARED = new GuiGraphicsExtractor();
	private final Pose pose = new Pose();

	private GuiGraphicsExtractor() {
	}

	public static GuiGraphicsExtractor get() {
		return SHARED;
	}

	/** What follows is drawn over what came before (1.8.9 draws in order: nothing to do). */
	public void nextStratum() {
		Frame.get().nextLayer();
	}

	/** The transform, as newer Minecraft's pose stack (the frame's own). */
	public Pose pose() {
		return pose;
	}

	/** {@code w × h} of a {@code texW × texH} texture from ({@code u}, {@code v}), one texel a pixel. */
	public void blit(Object pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH) {
		blit(pipeline, texture, x, y, u, v, w, h, w, h, texW, texH, 0xFFFFFFFF);
	}

	public void blit(Object pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH, int color) {
		blit(pipeline, texture, x, y, u, v, w, h, w, h, texW, texH, color);
	}

	public void blit(Object pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int uw, int vh, int texW, int texH) {
		blit(pipeline, texture, x, y, u, v, w, h, uw, vh, texW, texH, 0xFFFFFFFF);
	}

	/** The {@code uw × vh} region at ({@code u}, {@code v}) of a {@code texW × texH} texture, drawn into the pixel box and tinted. */
	public void blit(Object pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int uw, int vh, int texW, int texH,
		int color) {
		Frame.get().blitGame(texture.id(), x, y, x + w, y + h, u / texW, v / texH, (u + uw) / texW, (v + vh) / texH, color);
	}

	/** Newer Minecraft's 2D pose stack, on the frame's transform. */
	public static final class Pose {
		private Pose() {
		}

		public Pose pushMatrix() {
			Frame.get().pushTranslate(0, 0);
			return this;
		}

		public Pose popMatrix() {
			Frame.get().pop();
			return this;
		}

		/** Turns by {@code radians} clockwise about the pixel point ({@code x}, {@code y}). */
		public Pose rotateAbout(float radians, float x, float y) {
			Frame.get().rotate(radians, x, y);
			return this;
		}
	}
}
