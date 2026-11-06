package com.flintclient.client.compat;

import com.flintclient.client.mixin.feature.GuiGraphicsStateAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the maps' drawing: a convex textured polygon (a map tile cut to a round or turned map) as one GUI
 * element, its triangles fanned out as the GUI's quads. Minecraft's own blits are rectangles only; this is how a rotated,
 * round minimap draws straight from the map's tile textures with no off-screen pass.
 */
public final class MapQuads {
	private MapQuads() {
	}

	/**
	 * Draws the polygon {@code xy} ({@code n} points, x/y pairs, in the current pose's units) with texture coordinates
	 * {@code uv} (0..1) from {@code texture}, tinted {@code argb}, inside {@code scissor} (null: wherever it lands).
	 */
	public static void polygon(GuiGraphicsExtractor g, Identifier texture, float[] xy, float[] uv, int n, int argb, @Nullable ScreenRectangle scissor) {
		if (n < 3) {
			return;
		}
		AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
		// Nearest and clamped: map pixels stay crisp and a tile never samples its opposite edge.
		TextureSetup setup = TextureSetup.singleTexture(tex.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		float[] points = java.util.Arrays.copyOf(xy, 2 * n);
		float[] coords = java.util.Arrays.copyOf(uv, 2 * n);
		ScreenRectangle bounds = bounds(pose, points, n, scissor);
		if (bounds == null) {
			return;
		}
		((GuiGraphicsStateAccessor) g).flint$guiRenderState().addGuiElement(new Polygon(RenderPipelines.GUI_TEXTURED, setup, pose, points, coords, n,
			argb, scissor, bounds));
	}

	private static @Nullable ScreenRectangle bounds(Matrix3x2f pose, float[] points, int n, @Nullable ScreenRectangle scissor) {
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		Vector2f p = new Vector2f();
		for (int i = 0; i < n; i++) {
			pose.transformPosition(points[2 * i], points[2 * i + 1], p);
			minX = Math.min(minX, p.x);
			minY = Math.min(minY, p.y);
			maxX = Math.max(maxX, p.x);
			maxY = Math.max(maxY, p.y);
		}
		int x0 = (int) Math.floor(minX);
		int y0 = (int) Math.floor(minY);
		ScreenRectangle box = new ScreenRectangle(x0, y0, (int) Math.ceil(maxX) - x0, (int) Math.ceil(maxY) - y0);
		return scissor == null ? box : scissor.intersection(box);
	}

	private record Polygon(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, float[] points, float[] coords, int n, int color,
		@Nullable ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer consumer) {
			// A fan: (0, i, i + 1) as a quad whose last corner repeats.
			for (int i = 1; i + 1 < n; i++) {
				vertex(consumer, 0);
				vertex(consumer, i);
				vertex(consumer, i + 1);
				vertex(consumer, i + 1);
			}
		}

		private void vertex(VertexConsumer consumer, int i) {
			consumer.addVertexWith2DPose(pose, points[2 * i], points[2 * i + 1]).setUv(coords[2 * i], coords[2 * i + 1]).setColor(color);
		}
	}
}
