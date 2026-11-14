package com.flintclient.client.control.fx;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.platform.Frame;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.GameRenderer;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's menu blur kept to rectangles ({@link Frame#clipBlur}): the Right Shift window frosted over a game that stays sharp
 * around it. Minecraft's blur is a post pass over the whole main target, so it can't be scissored; instead, around it: the sharp
 * image is copied aside, the blur runs as usual, the blurred rectangles are copied into the sharp copy, and that goes back. Four
 * GPU copies a frame and Minecraft's own one blur (its one-blur-a-frame rule is untouched: this runs where its blur runs), only
 * while a clip is asked for; any other blur (the pause menu, the friends list over a Flint screen) is Minecraft's own.
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

	/**
	 * From {@code GuiRenderer.draw}, in place of its {@code processBlurEffect}: true when Minecraft should run its blur as usual (no
	 * clip asked this frame); false when this ran it, kept to the clip.
	 */
	public static boolean blurWhole(GameRenderer renderer) {
		int n = Frame.takeBlurClip(RECTS);
		if (n < 0 || !GUARD.on()) {
			if (n < 0 && sharp != null) {
				release();
			}
			return true;
		}
		boolean blurred = false;
		try {
			RenderTarget main = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
			GpuTexture color = main.getColorTexture();
			if (color == null) {
				return true;
			}
			int width = color.getWidth(0);
			int height = color.getHeight(0);
			RenderTarget aside = target(width, height, color.getFormat());
			GpuTexture copy = aside.getColorTexture();
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(color, copy, 0, 0, 0, 0, 0, width, height);
			renderer.processBlurEffect();
			blurred = true;
			for (int i = 0; i < n; i++) {
				int x0 = Math.max(0, RECTS[4 * i]);
				int x1 = Math.min(width, RECTS[4 * i + 2]);
				// The texture's rows run from the bottom of the window (as Minecraft's screenshots read them).
				int y0 = Math.max(0, height - RECTS[4 * i + 3]);
				int y1 = Math.min(height, height - RECTS[4 * i + 1]);
				if (x1 > x0 && y1 > y0) {
					encoder.copyTextureToTexture(color, copy, 0, x0, y0, x0, y0, x1 - x0, y1 - y0);
				}
			}
			encoder.copyTextureToTexture(copy, color, 0, 0, 0, 0, 0, width, height);
			return false;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
			// Blurred already: the screen stays blurred this frame (whole, as before the clip existed); else Minecraft blurs it.
			return !blurred;
		}
	}

	private static RenderTarget target(int width, int height, TextureFormat format) {
		RenderTarget kept = sharp;
		if (kept == null || kept.width != width || kept.height != height || kept.getColorTexture() == null
			|| kept.getColorTexture().getFormat() != format) {
			if (kept != null) {
				kept.destroyBuffers();
			}
			kept = new TextureTarget("Flint panel blur", width, height, false);
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
				// Already gone with the device; nothing more to free.
			}
		}
	}
}
