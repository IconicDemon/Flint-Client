package com.flintclient.client.compat;

import com.flintclient.client.control.platform.Mc263Glint;
import com.flintclient.client.control.platform.Mc263Tooltips;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.joml.Vector2ic;

/**
 * Tooltips on Minecraft 1.19.2, drawn as 1.20's GuiGraphics draws them: placed by a {@link ClientTooltipPositioner}, the
 * background, then the text and the pictures 400 units up (over the slots' items). Every tooltip goes through here, the game's
 * own too (mixin.feature.ScreenTooltipMixin sends Screen's here), so Scrollable Tooltips and Glint's backgrounds work on all of
 * them as they do on 1.20 and later (there, mixins on GuiGraphics and TooltipRenderUtil do it).
 */
public final class Tooltips {
	private static final int BACKGROUND = 0xF0100010;
	private static final int BORDER_TOP = 0x505000FF;
	private static final int BORDER_BOTTOM = 0x5028007F;
	private static final int Z = 400;

	private Tooltips() {
	}

	public static void draw(GuiGraphics graphics, Font font, List<ClientTooltipComponent> lines, int mouseX, int mouseY,
		ClientTooltipPositioner positioner) {
		if (lines.isEmpty()) {
			return;
		}
		int width = 0;
		int height = lines.size() == 1 ? -2 : 0;
		for (ClientTooltipComponent line : lines) {
			width = Math.max(width, line.getWidth(font));
			height += line.getHeight();
		}
		int w = width;
		int h = height;
		Vector2ic placed = Mc263Tooltips.place((sw, sh, mx, my, tw, th) -> positioner.positionTooltip(sw, sh, mx, my, tw, th), graphics.guiWidth(),
			graphics.guiHeight(), mouseX, mouseY, w, h, lines.size());
		if (placed == null) {
			placed = positioner.positionTooltip(graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY, w, h);
		}
		int x = placed.x();
		int y = placed.y();
		PoseStack pose = graphics.pose();
		pose.pushPose();
		Mc263Tooltips.transform(graphics.pose2d());
		ItemRenderer items = Minecraft.getInstance().getItemRenderer();
		float itemsZ = items.blitOffset;
		items.blitOffset = Z;
		try {
			background(graphics, x, y, w, h);
			pose.translate(0.0, 0.0, Z);
			MultiBufferSource.BufferSource buffers = graphics.bufferSource();
			Matrix4f matrix = pose.last().pose();
			int lineY = y;
			for (int i = 0; i < lines.size(); i++) {
				ClientTooltipComponent line = lines.get(i);
				line.renderText(font, x, lineY, matrix, buffers);
				lineY += line.getHeight() + (i == 0 ? 2 : 0);
			}
			buffers.endBatch();
			pose.translate(0.0, 0.0, -Z);
			lineY = y;
			for (int i = 0; i < lines.size(); i++) {
				ClientTooltipComponent line = lines.get(i);
				ClientTooltipComponent image = line;
				int at = lineY;
				graphics.withPose(() -> image.renderImage(font, x, at, new PoseStack(), items, Z));
				lineY += line.getHeight() + (i == 0 ? 2 : 0);
			}
		} finally {
			items.blitOffset = itemsZ;
			pose.popPose();
		}
	}

	/** The tooltip's box (Glint's when it has one for the tooltip's style), at the tooltip's depth. */
	private static void background(GuiGraphics graphics, int x, int y, int w, int h) {
		PoseStack pose = graphics.pose();
		pose.pushPose();
		pose.translate(0.0, 0.0, Z);
		try {
			if (!Mc263Glint.background(graphics, x, y, w, h, TooltipStyle.current())) {
				vanillaBackground(pose.last().pose(), x, y, w, h);
			}
			Mc263Glint.afterBackground(graphics, x, y, TooltipStyle.current());
		} finally {
			pose.popPose();
		}
	}

	/** Minecraft's tooltip box, as 1.19.2's Screen draws it (z 0 here: the pose holds the depth). */
	private static void vanillaBackground(Matrix4f m, int x, int y, int w, int h) {
		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		GuiGraphics.gradient(m, buffer, x - 3, y - 4, x + w + 3, y - 3, 0, BACKGROUND, BACKGROUND);
		GuiGraphics.gradient(m, buffer, x - 3, y + h + 3, x + w + 3, y + h + 4, 0, BACKGROUND, BACKGROUND);
		GuiGraphics.gradient(m, buffer, x - 3, y - 3, x + w + 3, y + h + 3, 0, BACKGROUND, BACKGROUND);
		GuiGraphics.gradient(m, buffer, x - 4, y - 3, x - 3, y + h + 3, 0, BACKGROUND, BACKGROUND);
		GuiGraphics.gradient(m, buffer, x + w + 3, y - 3, x + w + 4, y + h + 3, 0, BACKGROUND, BACKGROUND);
		GuiGraphics.gradient(m, buffer, x - 3, y - 3 + 1, x - 3 + 1, y + h + 3 - 1, 0, BORDER_TOP, BORDER_BOTTOM);
		GuiGraphics.gradient(m, buffer, x + w + 2, y - 3 + 1, x + w + 3, y + h + 3 - 1, 0, BORDER_TOP, BORDER_BOTTOM);
		GuiGraphics.gradient(m, buffer, x - 3, y - 3, x + w + 3, y - 3 + 1, 0, BORDER_TOP, BORDER_TOP);
		GuiGraphics.gradient(m, buffer, x - 3, y + h + 2, x + w + 3, y + h + 3, 0, BORDER_BOTTOM, BORDER_BOTTOM);
		RenderSystem.enableDepthTest();
		RenderSystem.disableTexture();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		BufferUploader.drawWithShader(buffer.end());
		RenderSystem.disableBlend();
		RenderSystem.enableTexture();
	}
}
