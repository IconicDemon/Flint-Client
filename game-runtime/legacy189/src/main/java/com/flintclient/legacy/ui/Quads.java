package com.flintclient.legacy.ui;

import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.client.render.vertex.Tesselator;
import org.lwjgl.opengl.GL11;

/**
 * Plain rectangles for the HUD (GUI pixels), several in one draw: {@link #begin}, any number of {@link #rect}, {@link #end}.
 * Minecraft's own fill is one draw (and a round of state changes) per rectangle. Nothing else may use the tessellator between
 * begin and end. Render thread.
 */
public final class Quads {
	private static boolean open;

	private Quads() {
	}

	public static void begin() {
		Tesselator.getInstance().getBuffer().begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_COLOR);
		open = true;
	}

	/** A rectangle from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}); skipped when fully see-through. */
	public static void rect(float x0, float y0, float x1, float y1, int argb) {
		int a = argb >>> 24;
		if (a == 0 || !open) {
			return;
		}
		int r = (argb >> 16) & 255;
		int g = (argb >> 8) & 255;
		int b = argb & 255;
		BufferBuilder buf = Tesselator.getInstance().getBuffer();
		buf.vertex(x0, y1, 0).color(r, g, b, a).nextVertex();
		buf.vertex(x1, y1, 0).color(r, g, b, a).nextVertex();
		buf.vertex(x1, y0, 0).color(r, g, b, a).nextVertex();
		buf.vertex(x0, y0, 0).color(r, g, b, a).nextVertex();
	}

	public static void end() {
		if (!open) {
			return;
		}
		open = false;
		GlStateManager.enableBlend();
		GlStateManager.disableTexture();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.color4f(1, 1, 1, 1);
		Tesselator.getInstance().end();
		GlStateManager.enableTexture();
	}
}
