package com.flintclient.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.1's {@code GuiGraphics} under 26.x's names. 1.21.1 draws as it is asked (no render state, no strata, no
 * pipelines): text and fills go through its buffer source and are drawn at once, textured quads straight to the GPU.
 * Loom adds this interface to GuiGraphics at compile time ({@code src/mc12101/loom/fabric.mod.json}) and
 * {@code mixin.GraphicsBridgeMixin} at run time (which also keeps the 2D {@link #pose2d() pose} each GuiGraphics has).
 * Everything is drawn in the order asked, with depth testing off, as 26.x's GUI is.
 */
public interface GraphicsBridge {
	private GuiGraphics self() {
		return (GuiGraphics) (Object) this;
	}

	/** 26.x's 2D pose stack over this GuiGraphics' PoseStack (the line's rules call {@code pose()} this). */
	default Pose2D pose2d() {
		throw new IllegalStateException("GraphicsBridgeMixin isn't applied");
	}

	// ---------- strata and cursors: 1.21.1 draws in call order ----------

	default void nextStratum() {
	}

	/** Blurs everything drawn so far, as a screen's blurred background does on 1.21.1 (the menu blur amount in the options). */
	default void blurBeforeThisStratum() {
		Minecraft mc = Minecraft.getInstance();
		self().flush();
		mc.gameRenderer.processBlurEffect(mc.getTimer().getGameTimeDeltaPartialTick(false));
		mc.getMainRenderTarget().bindWrite(false);
	}

	// ---------- shapes ----------

	default void horizontalLine(int x0, int x1, int y, int color) {
		self().hLine(x0, x1, y, color);
	}

	default void verticalLine(int x, int y0, int y1, int color) {
		self().vLine(x, y0, y1, color);
	}

	default void outline(int x, int y, int width, int height, int color) {
		self().renderOutline(x, y, width, height, color);
	}

	default void submitOutline(int x, int y, int width, int height, int color) {
		self().renderOutline(x, y, width, height, color);
	}

	default void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int color) {
		if (pipeline.invert()) {
			self().fill(RenderType.guiTextHighlight(), x0, y0, x1, y1, color);
		} else {
			self().fill(x0, y0, x1, y1, color);
		}
	}

	/** The inverted box behind selected text. */
	default void textHighlight(int x0, int y0, int x1, int y1) {
		self().fill(RenderType.guiTextHighlight(), x0, y0, x1, y1, 0xFF0000FF);
	}

	/**
	 * A scissor rectangle in GUI units mapped through the current pose (26.x's enableScissor does that; 1.21.1's takes the
	 * rectangle as given): the smallest whole-unit box around the moved corners.
	 */
	default void flintScissor(int x0, int y0, int x1, int y1) {
		Matrix4f pose = self().pose().last().pose();
		Vector3f a = pose.transformPosition(x0, y0, 0, new Vector3f());
		Vector3f b = pose.transformPosition(x1, y1, 0, new Vector3f());
		self().enableScissor((int) Math.floor(Math.min(a.x, b.x)), (int) Math.floor(Math.min(a.y, b.y)), (int) Math.ceil(Math.max(a.x, b.x)),
			(int) Math.ceil(Math.max(a.y, b.y)));
	}

	// ---------- textures ----------

	default void blit(RenderPipeline pipeline, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth,
		int textureHeight) {
		blit(pipeline, texture, x, y, u, v, width, height, width, height, textureWidth, textureHeight, -1);
	}

	default void blit(RenderPipeline pipeline, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth,
		int textureHeight, int color) {
		blit(pipeline, texture, x, y, u, v, width, height, width, height, textureWidth, textureHeight, color);
	}

	default void blit(RenderPipeline pipeline, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int uWidth, int vHeight,
		int textureWidth, int textureHeight) {
		blit(pipeline, texture, x, y, u, v, width, height, uWidth, vHeight, textureWidth, textureHeight, -1);
	}

	/** A {@code width × height} box showing the texture's {@code uWidth × vHeight} region at (u, v) (negative sizes mirror it), tinted. */
	default void blit(RenderPipeline pipeline, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int uWidth, int vHeight,
		int textureWidth, int textureHeight, int color) {
		quad(pipeline, texture, x, x + width, y, y + height, u / textureWidth, (u + uWidth) / textureWidth, v / textureHeight, (v + vHeight) / textureHeight,
			color);
	}

	/**
	 * One textured, tinted quad, drawn now with blending on and depth testing off. The tint goes on the shader colour with
	 * Minecraft's plain textured shader: 1.21.1's coloured one drops every pixel under 10% opacity, which would erase faint
	 * surfaces and the soft edges of Flint's shapes (26.x's GUI pipeline keeps them).
	 */
	default void quad(RenderPipeline pipeline, ResourceLocation texture, float x0, float x1, float y0, float y1, float u0, float u1, float v0, float v1,
		int color) {
		float[] shaderColor = RenderSystem.getShaderColor();
		float r = shaderColor[0];
		float g = shaderColor[1];
		float b = shaderColor[2];
		float a = shaderColor[3];
		RenderSystem.setShaderColor(r * (color >> 16 & 0xFF) / 255.0F, g * (color >> 8 & 0xFF) / 255.0F, b * (color & 0xFF) / 255.0F,
			a * (color >>> 24) / 255.0F);
		RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.enableBlend();
		if (pipeline.invert()) {
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		} else {
			RenderSystem.defaultBlendFunc();
		}
		RenderSystem.disableDepthTest();
		Matrix4f pose = self().pose().last().pose();
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		buffer.addVertex(pose, x0, y0, 0).setUv(u0, v0);
		buffer.addVertex(pose, x0, y1, 0).setUv(u0, v1);
		buffer.addVertex(pose, x1, y1, 0).setUv(u1, v1);
		buffer.addVertex(pose, x1, y0, 0).setUv(u1, v0);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		RenderSystem.enableDepthTest();
		RenderSystem.setShaderColor(r, g, b, a);
	}

	default void blitSprite(RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int width, int height) {
		blitSprite(pipeline, sprite, x, y, width, height, -1);
	}

	default void blitSprite(RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int width, int height, float alpha) {
		blitSprite(pipeline, sprite, x, y, width, height, ((int) (alpha * 255.0F + 0.5F) & 0xFF) << 24 | 0xFFFFFF);
	}

	default void blitSprite(RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int width, int height, int color) {
		// An effect icon (mob_effect/<effect>, as 26.x names it in the GUI atlas): 1.21.1 keeps those in the mob_effects atlas.
		net.minecraft.client.renderer.texture.TextureAtlasSprite effect = McCompat.effectSprite(sprite);
		if (effect != null) {
			tinted(pipeline, color, () -> self().blit(x, y, 0, width, height, effect));
			return;
		}
		tinted(pipeline, color, () -> self().blitSprite(sprite, x, y, width, height));
	}

	default void blitSprite(RenderPipeline pipeline, ResourceLocation sprite, int textureWidth, int textureHeight, int u, int v, int x, int y, int width,
		int height) {
		blitSprite(pipeline, sprite, textureWidth, textureHeight, u, v, x, y, width, height, -1);
	}

	default void blitSprite(RenderPipeline pipeline, ResourceLocation sprite, int textureWidth, int textureHeight, int u, int v, int x, int y, int width,
		int height, int color) {
		tinted(pipeline, color, () -> self().blitSprite(sprite, textureWidth, textureHeight, u, v, x, y, width, height));
	}

	/** Runs a vanilla draw with the shader colour (and the pipeline's blending) set, then puts them back. */
	private void tinted(RenderPipeline pipeline, int color, Runnable draw) {
		boolean tint = color != -1;
		if (tint) {
			RenderSystem.setShaderColor((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
		}
		RenderSystem.enableBlend();
		if (pipeline.invert()) {
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		} else {
			RenderSystem.defaultBlendFunc();
		}
		RenderSystem.disableDepthTest();
		try {
			draw.run();
		} finally {
			RenderSystem.enableDepthTest();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
			if (tint) {
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			}
		}
	}

	// ---------- text ----------

	default void text(Font font, @Nullable String text, int x, int y, int color) {
		self().drawString(font, text, x, y, color);
	}

	default void text(Font font, @Nullable String text, int x, int y, int color, boolean shadow) {
		self().drawString(font, text, x, y, color, shadow);
	}

	default void text(Font font, FormattedCharSequence text, int x, int y, int color) {
		self().drawString(font, text, x, y, color);
	}

	default void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		self().drawString(font, text, x, y, color, shadow);
	}

	default void text(Font font, Component text, int x, int y, int color) {
		self().drawString(font, text, x, y, color);
	}

	default void text(Font font, Component text, int x, int y, int color, boolean shadow) {
		self().drawString(font, text, x, y, color, shadow);
	}

	default void centeredText(Font font, String text, int x, int y, int color) {
		self().drawCenteredString(font, text, x, y, color);
	}

	default void centeredText(Font font, Component text, int x, int y, int color) {
		self().drawCenteredString(font, text, x, y, color);
	}

	default void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
		self().drawCenteredString(font, text, x, y, color);
	}

	default void textWithWordWrap(Font font, FormattedText text, int x, int y, int width, int color) {
		textWithWordWrap(font, text, x, y, width, color, false);
	}

	default void textWithWordWrap(Font font, FormattedText text, int x, int y, int width, int color, boolean shadow) {
		for (FormattedCharSequence line : font.split(text, width)) {
			self().drawString(font, line, x, y, color, shadow);
			y += font.lineHeight;
		}
	}

	default void textWithBackdrop(Font font, Component text, int x, int y, int width, int color) {
		self().drawStringWithBackdrop(font, text, x, y, width, color);
	}

	// ---------- items ----------

	default void item(ItemStack stack, int x, int y) {
		self().renderItem(stack, x, y);
	}

	default void item(ItemStack stack, int x, int y, int seed) {
		self().renderItem(stack, x, y, seed);
	}

	default void item(LivingEntity entity, ItemStack stack, int x, int y, int seed) {
		self().renderItem(entity, stack, x, y, seed);
	}

	default void fakeItem(ItemStack stack, int x, int y) {
		self().renderFakeItem(stack, x, y);
	}

	default void fakeItem(ItemStack stack, int x, int y, int seed) {
		self().renderFakeItem(stack, x, y, seed);
	}

	default void itemDecorations(Font font, ItemStack stack, int x, int y) {
		self().renderItemDecorations(font, stack, x, y);
	}

	default void itemDecorations(Font font, ItemStack stack, int x, int y, @Nullable String count) {
		self().renderItemDecorations(font, stack, x, y, count);
	}

	// ---------- tooltips ----------

	/** A tooltip drawn now, above everything (1.21.1 has no tooltip styles: {@code style} is Minecraft's own look). */
	default void tooltip(Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner,
		@Nullable ResourceLocation style) {
		Tooltips.draw(self(), font, lines, x, y, positioner);
	}

	/** As 26.x: the tooltip is drawn after the screen (here: the open screen's deferred tooltip, or now without one). */
	default void setTooltipForNextFrame(Font font, List<FormattedCharSequence> lines, ClientTooltipPositioner positioner, int x, int y,
		boolean focused) {
		Screen screen = Minecraft.getInstance().screen;
		if (screen != null) {
			screen.setTooltipForNextRenderPass(lines, positioner, focused);
		} else {
			self().renderTooltip(font, lines, positioner, x, y);
		}
	}

	default void setTooltipForNextFrame(Font font, List<? extends FormattedCharSequence> lines, int x, int y) {
		@SuppressWarnings("unchecked")
		List<FormattedCharSequence> list = (List<FormattedCharSequence>) lines;
		setTooltipForNextFrame(font, list, DefaultTooltipPositioner.INSTANCE, x, y, false);
	}

	default void setTooltipForNextFrame(Font font, Component text, int x, int y) {
		setTooltipForNextFrame(font, List.of(text.getVisualOrderText()), DefaultTooltipPositioner.INSTANCE, x, y, false);
	}

	default void setComponentTooltipForNextFrame(Font font, List<Component> lines, int x, int y) {
		setTooltipForNextFrame(font, lines.stream().map(Component::getVisualOrderText).toList(), DefaultTooltipPositioner.INSTANCE, x, y, false);
	}
}
