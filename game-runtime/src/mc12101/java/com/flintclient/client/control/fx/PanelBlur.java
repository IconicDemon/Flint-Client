package com.flintclient.client.control.fx;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.platform.Frame;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL30;

/**
 * Minecraft's menu blur kept to rectangles ({@link Frame#clipBlur}) on 1.21.1, as 26.3's: the Right Shift window frosted over a
 * game that stays sharp around it. 1.21.1 blurs at once when a screen asks (compat/GraphicsBridge.blurBeforeThisStratum), before
 * the screen has named its rectangles, so the rectangles are the ones it named the frame before (where the window was drawn a
 * frame ago: the same place but for the first frame, which blurs whole, and an open or close animation's step). Around
 * Minecraft's own blur pass: the sharp image is copied aside, the blur runs as usual, the blurred rectangles are copied into
 * the sharp copy, and that goes back (framebuffer blits).
 *
 * <p>The copy target is the window's size and is kept between frames; it goes once a whole-screen blur runs. If a step throws,
 * the clip is off for the session and the menu blurs the whole screen again. Render thread.
 */
public final class PanelBlur {
	private static final HookGuard GUARD = new HookGuard("panel blur",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} failed; the menu blurs the whole screen for this session", path, error));
	private static final int[] RECTS = new int[4 * Frame.BLUR_CLIPS];
	private static @Nullable RenderTarget sharp;

	private PanelBlur() {
	}

	/** In place of {@code processBlurEffect(partialTick)}: Minecraft's blur, kept to the rectangles asked for (the whole screen without). */
	public static void blur(Minecraft mc, float partialTick) {
		int n = Frame.takeBlurClip(RECTS);
		if (n < 0 || !GUARD.on()) {
			if (n < 0 && sharp != null) {
				release();
			}
			mc.gameRenderer.processBlurEffect(partialTick);
			return;
		}
		boolean blurred = false;
		try {
			RenderTarget main = mc.getMainRenderTarget();
			int width = main.width;
			int height = main.height;
			RenderTarget aside = target(width, height);
			copy(main, aside, 0, 0, width, height);
			mc.gameRenderer.processBlurEffect(partialTick);
			blurred = true;
			for (int i = 0; i < n; i++) {
				int x0 = Math.max(0, RECTS[4 * i]);
				int x1 = Math.min(width, RECTS[4 * i + 2]);
				// Framebuffer rows run from the bottom of the window.
				int y0 = Math.max(0, height - RECTS[4 * i + 3]);
				int y1 = Math.min(height, height - RECTS[4 * i + 1]);
				if (x1 > x0 && y1 > y0) {
					copy(main, aside, x0, y0, x1, y1);
				}
			}
			copy(aside, main, 0, 0, width, height);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
			if (!blurred) {
				mc.gameRenderer.processBlurEffect(partialTick);
			}
		} finally {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		}
	}

	private static void copy(RenderTarget from, RenderTarget to, int x0, int y0, int x1, int y1) {
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
		GlStateManager._glBlitFrameBuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
	}

	private static RenderTarget target(int width, int height) {
		RenderTarget kept = sharp;
		if (kept == null || kept.width != width || kept.height != height) {
			if (kept != null) {
				kept.destroyBuffers();
			}
			kept = new TextureTarget(width, height, false, Minecraft.ON_OSX);
			sharp = kept;
		}
		return kept;
	}

	private static void release() {
		RenderTarget kept = sharp;
		sharp = null;
		if (kept != null) {
			try {
				kept.destroyBuffers();
			} catch (RuntimeException e) {
				// Already gone with the context; nothing more to free.
			}
		}
	}
}
