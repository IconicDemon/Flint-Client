package com.flintclient.client.control.fx;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.mixin.fx.PanelBlurMixin;
import com.flintclient.client.platform.Frame;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL30;

/**
 * The Right Shift menu's blur on Minecraft 1.19.2 and 1.19, kept to rectangles ({@link Frame#clipBlur}) as 26.3's: the window
 * frosted over a game that stays sharp around it. 1.19 has no menu blur (1.20.5 added it), so this is that blur as 1.20.5 made
 * it: Minecraft's own blur post effect ({@code shaders/post/blur.json}) over the main target, its Radius the menu's blur
 * strength, run twice (1.20.5's blur makes two passes each way). It runs at once when the menu asks
 * ({@code compat/GraphicsBridge.blurBeforeThisStratum}), before the menu has named its rectangles, so the rectangles are the
 * ones it named the frame before (where the window was drawn a frame ago: the same place but for the first frame, which blurs
 * whole, and an open or close animation's step). Around the blur: the sharp image is copied aside, the blur runs, the blurred
 * rectangles are copied into the sharp copy, and that goes back (framebuffer blits).
 *
 * <p>The copy target is the window's size and is kept between frames; it goes once a whole-screen blur runs. If a step
 * throws, the blur is off for the session (the menu shows unblurred, as before 1.19 had it). Render thread.
 */
public final class PanelBlur {
	private static final HookGuard GUARD = new HookGuard("panel blur",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} failed; the menu isn't blurred for this session", path, error));
	private static final int[] RECTS = new int[4 * Frame.BLUR_CLIPS];
	private static @Nullable RenderTarget sharp;
	private static @Nullable PostChain chain;
	private static int chainWidth;
	private static int chainHeight;

	private PanelBlur() {
	}

	/** The menu's blur of {@code strength} (1..10) over what is drawn so far, kept to the rectangles asked for (whole without). */
	public static void blur(Minecraft mc, float strength, float partialTick) {
		int n = Frame.takeBlurClip(RECTS);
		if (!GUARD.on() || strength < 1.0F) {
			return;
		}
		try {
			RenderTarget main = mc.getMainRenderTarget();
			int width = main.width;
			int height = main.height;
			PostChain effect = chain(mc, width, height);
			for (PostPass pass : ((PanelBlurMixin) effect).flint$passes()) {
				pass.getEffect().safeGetUniform("Radius").set(strength);
			}
			if (n < 0) {
				if (sharp != null) {
					release();
				}
				process(effect, partialTick);
				return;
			}
			RenderTarget aside = target(width, height);
			copy(main, aside, 0, 0, width, height);
			process(effect, partialTick);
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
		} catch (RuntimeException | LinkageError | java.io.IOException e) {
			GUARD.fail(e);
			release();
		} finally {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
			mc.getMainRenderTarget().bindWrite(false);
			RenderSystem.enableDepthTest();
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
		}
	}

	/** Two passes each way, as 1.20.5's menu blur (its blur.json has four). */
	private static void process(PostChain effect, float partialTick) {
		effect.process(partialTick);
		effect.process(partialTick);
	}

	private static PostChain chain(Minecraft mc, int width, int height) throws java.io.IOException {
		PostChain c = chain;
		if (c == null) {
			c = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), new ResourceLocation("shaders/post/blur.json"));
			chain = c;
			chainWidth = -1;
		}
		if (chainWidth != width || chainHeight != height) {
			c.resize(width, height);
			chainWidth = width;
			chainHeight = height;
		}
		return c;
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
