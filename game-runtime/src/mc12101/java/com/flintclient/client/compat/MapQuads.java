package com.flintclient.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * Minecraft 1.21.1's side of the maps' drawing: a convex textured polygon (a map tile cut to a round or turned map) drawn
 * at once as a triangle fan, through the current pose, inside an optional scissor box.
 */
public final class MapQuads {
	private MapQuads() {
	}

	/**
	 * Draws the polygon {@code xy} ({@code n} points, x/y pairs, in the current pose's units) with texture coordinates
	 * {@code uv} (0..1) from {@code texture}, tinted {@code argb}, inside {@code scissor} (null: wherever it lands).
	 */
	public static void polygon(GuiGraphics g, ResourceLocation texture, float[] xy, float[] uv, int n, int argb, @Nullable ScreenRectangle scissor) {
		if (n < 3) {
			return;
		}
		AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
		// Nearest and clamped: map pixels stay crisp and a tile never samples its opposite edge.
		tex.setFilter(false, false);
		tex.bind();
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
		if (scissor != null) {
			g.enableScissor(scissor.left(), scissor.top(), scissor.right(), scissor.bottom());
		}
		RenderSystem.setShaderTexture(0, texture);
		float[] shaderColor = RenderSystem.getShaderColor();
		float r = shaderColor[0];
		float gr = shaderColor[1];
		float b = shaderColor[2];
		float a = shaderColor[3];
		// The tint on the shader colour: 1.21.1's coloured shader drops pixels under 10% opacity.
		RenderSystem.setShaderColor(r * (argb >> 16 & 0xFF) / 255.0F, gr * (argb >> 8 & 0xFF) / 255.0F, b * (argb & 0xFF) / 255.0F, a * (argb >>> 24) / 255.0F);
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		Matrix4f pose = g.pose().last().pose();
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX);
		for (int i = 0; i < n; i++) {
			buffer.addVertex(pose, xy[2 * i], xy[2 * i + 1], 0).setUv(uv[2 * i], uv[2 * i + 1]);
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.disableBlend();
		RenderSystem.enableDepthTest();
		RenderSystem.setShaderColor(r, gr, b, a);
		if (scissor != null) {
			g.disableScissor();
		}
	}
}
