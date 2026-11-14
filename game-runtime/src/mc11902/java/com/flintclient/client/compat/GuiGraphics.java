package com.flintclient.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.20's {@code GuiGraphics} for Minecraft 1.19.x, which draws its GUI through a {@link PoseStack} and
 * {@code GuiComponent}'s static helpers instead (shared-sources.rules points every use of GuiGraphics here). One is made for
 * the PoseStack Minecraft hands a screen, the HUD or a widget ({@link #of}); it draws with 1.19's own calls, at once and in
 * the order asked, as 1.20's GuiGraphics does outside its managed batches. 26.x's names on top ({@link GraphicsBridge}) come
 * from the 1.21.1 overlay's bridge, which this class implements.
 */
public class GuiGraphics implements GraphicsBridge {
	public static final float MAX_GUI_Z = 10000.0F;
	public static final float MIN_GUI_Z = -10000.0F;

	private static @Nullable GuiGraphics last;

	private final Minecraft minecraft;
	private final PoseStack pose;
	private final MultiBufferSource.BufferSource bufferSource;
	private final Deque<int[]> scissors = new ArrayDeque<>();
	private @Nullable Pose2D pose2d;

	public GuiGraphics(Minecraft minecraft, PoseStack pose, MultiBufferSource.BufferSource bufferSource) {
		this.minecraft = minecraft;
		this.pose = pose;
		this.bufferSource = bufferSource;
	}

	public GuiGraphics(Minecraft minecraft, MultiBufferSource.BufferSource bufferSource) {
		this(minecraft, new PoseStack(), bufferSource);
	}

	/**
	 * The GuiGraphics over {@code pose}: the same object as long as Minecraft keeps handing out the same PoseStack (one per
	 * frame for the HUD and the screen), so a frame's drawing is one target ({@code platform.Frame#target}).
	 */
	public static GuiGraphics of(PoseStack pose) {
		GuiGraphics graphics = last;
		if (graphics == null || graphics.pose != pose) {
			Minecraft mc = Minecraft.getInstance();
			graphics = new GuiGraphics(mc, pose, mc.renderBuffers().bufferSource());
			last = graphics;
		}
		return graphics;
	}

	public int guiWidth() {
		return minecraft.getWindow().getGuiScaledWidth();
	}

	public int guiHeight() {
		return minecraft.getWindow().getGuiScaledHeight();
	}

	public PoseStack pose() {
		return pose;
	}

	@Override
	public Pose2D pose2d() {
		Pose2D view = pose2d;
		if (view == null) {
			view = new Pose2D(pose);
			pose2d = view;
		}
		return view.sync();
	}

	public MultiBufferSource.BufferSource bufferSource() {
		return bufferSource;
	}

	/** Draws what was batched into {@link #bufferSource} so far. */
	public void flush() {
		RenderSystem.disableDepthTest();
		bufferSource.endBatch();
		RenderSystem.enableDepthTest();
	}

	public void drawManaged(Runnable draw) {
		draw.run();
		flush();
	}

	// ---------- lines and fills ----------

	public void hLine(int x0, int x1, int y, int color) {
		if (x1 < x0) {
			int t = x0;
			x0 = x1;
			x1 = t;
		}
		fill(x0, y, x1 + 1, y + 1, color);
	}

	public void vLine(int x, int y0, int y1, int color) {
		if (y1 < y0) {
			int t = y0;
			y0 = y1;
			y1 = t;
		}
		fill(x, y0 + 1, x + 1, y1, color);
	}

	public void renderOutline(int x, int y, int width, int height, int color) {
		fill(x, y, x + width, y + 1, color);
		fill(x, y + height - 1, x + width, y + height, color);
		fill(x, y + 1, x + 1, y + height - 1, color);
		fill(x + width - 1, y + 1, x + width, y + height - 1, color);
	}

	public void fill(int x0, int y0, int x1, int y1, int color) {
		fill(x0, y0, x1, y1, 0, color);
	}

	public void fill(int x0, int y0, int x1, int y1, int z, int color) {
		if (x0 < x1) {
			int t = x0;
			x0 = x1;
			x1 = t;
		}
		if (y0 < y1) {
			int t = y0;
			y0 = y1;
			y1 = t;
		}
		float a = (color >>> 24) / 255.0F;
		float r = (color >> 16 & 0xFF) / 255.0F;
		float g = (color >> 8 & 0xFF) / 255.0F;
		float b = (color & 0xFF) / 255.0F;
		Matrix4f matrix = pose.last().pose();
		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		RenderSystem.enableBlend();
		RenderSystem.disableTexture();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buffer.vertex(matrix, x0, y1, z).color(r, g, b, a).endVertex();
		buffer.vertex(matrix, x1, y1, z).color(r, g, b, a).endVertex();
		buffer.vertex(matrix, x1, y0, z).color(r, g, b, a).endVertex();
		buffer.vertex(matrix, x0, y0, z).color(r, g, b, a).endVertex();
		BufferUploader.drawWithShader(buffer.end());
		RenderSystem.enableTexture();
		RenderSystem.disableBlend();
	}

	/** A box that inverts what is under it (a text cursor's selection, the crosshair), as 1.19's EditBox draws one. */
	public void fillInverted(int x0, int y0, int x1, int y1) {
		if (x0 < x1) {
			int t = x0;
			x0 = x1;
			x1 = t;
		}
		if (y0 < y1) {
			int t = y0;
			y0 = y1;
			y1 = t;
		}
		Matrix4f matrix = pose.last().pose();
		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		RenderSystem.setShader(GameRenderer::getPositionShader);
		RenderSystem.setShaderColor(0.0F, 0.0F, 1.0F, 1.0F);
		RenderSystem.disableTexture();
		RenderSystem.enableColorLogicOp();
		RenderSystem.logicOp(GlStateManager.LogicOp.OR_REVERSE);
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
		buffer.vertex(matrix, x0, y1, 0).endVertex();
		buffer.vertex(matrix, x1, y1, 0).endVertex();
		buffer.vertex(matrix, x1, y0, 0).endVertex();
		buffer.vertex(matrix, x0, y0, 0).endVertex();
		BufferUploader.drawWithShader(buffer.end());
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.disableColorLogicOp();
		RenderSystem.enableTexture();
	}

	public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
		fillGradient(x0, y0, x1, y1, 0, top, bottom);
	}

	public void fillGradient(int x0, int y0, int x1, int y1, int z, int top, int bottom) {
		RenderSystem.disableTexture();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		gradient(pose.last().pose(), buffer, x0, y0, x1, y1, z, top, bottom);
		BufferUploader.drawWithShader(buffer.end());
		RenderSystem.disableBlend();
		RenderSystem.enableTexture();
	}

	static void gradient(Matrix4f matrix, BufferBuilder buffer, int x0, int y0, int x1, int y1, int z, int top, int bottom) {
		float a0 = (top >>> 24) / 255.0F;
		float r0 = (top >> 16 & 0xFF) / 255.0F;
		float g0 = (top >> 8 & 0xFF) / 255.0F;
		float b0 = (top & 0xFF) / 255.0F;
		float a1 = (bottom >>> 24) / 255.0F;
		float r1 = (bottom >> 16 & 0xFF) / 255.0F;
		float g1 = (bottom >> 8 & 0xFF) / 255.0F;
		float b1 = (bottom & 0xFF) / 255.0F;
		buffer.vertex(matrix, x1, y0, z).color(r0, g0, b0, a0).endVertex();
		buffer.vertex(matrix, x0, y0, z).color(r0, g0, b0, a0).endVertex();
		buffer.vertex(matrix, x0, y1, z).color(r1, g1, b1, a1).endVertex();
		buffer.vertex(matrix, x1, y1, z).color(r1, g1, b1, a1).endVertex();
	}

	// ---------- scissor: a stack of rectangles in GUI units, each inside the one under it (as 1.20's) ----------

	public void enableScissor(int x0, int y0, int x1, int y1) {
		int[] box = {x0, y0, x1, y1};
		int[] outer = scissors.peekLast();
		if (outer != null) {
			box[0] = Math.max(box[0], outer[0]);
			box[1] = Math.max(box[1], outer[1]);
			box[2] = Math.max(box[0], Math.min(box[2], outer[2]));
			box[3] = Math.max(box[1], Math.min(box[3], outer[3]));
		}
		scissors.addLast(box);
		applyScissor(box);
	}

	public void disableScissor() {
		if (scissors.isEmpty()) {
			throw new IllegalStateException("Scissor stack underflow");
		}
		scissors.removeLast();
		applyScissor(scissors.peekLast());
	}

	public boolean containsPointInScissor(int x, int y) {
		int[] box = scissors.peekLast();
		return box == null || x >= box[0] && x < box[2] && y >= box[1] && y < box[3];
	}

	private void applyScissor(int @Nullable [] box) {
		flush();
		if (box == null) {
			RenderSystem.disableScissor();
			return;
		}
		Window window = minecraft.getWindow();
		double scale = window.getGuiScale();
		double x = box[0] * scale;
		double y = window.getHeight() - box[3] * scale;
		double w = (box[2] - box[0]) * scale;
		double h = (box[3] - box[1]) * scale;
		RenderSystem.enableScissor((int) x, (int) y, Math.max(0, (int) w), Math.max(0, (int) h));
	}

	public void setColor(float r, float g, float b, float a) {
		flush();
		RenderSystem.setShaderColor(r, g, b, a);
	}

	// ---------- text ----------

	public void drawCenteredString(Font font, String text, int x, int y, int color) {
		drawString(font, text, x - font.width(text) / 2, y, color);
	}

	public void drawCenteredString(Font font, Component text, int x, int y, int color) {
		FormattedCharSequence line = text.getVisualOrderText();
		drawString(font, line, x - font.width(line) / 2, y, color);
	}

	public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
		drawString(font, text, x - font.width(text) / 2, y, color);
	}

	public int drawString(Font font, @Nullable String text, int x, int y, int color) {
		return drawString(font, text, x, y, color, true);
	}

	public int drawString(Font font, @Nullable String text, int x, int y, int color, boolean shadow) {
		if (text == null) {
			return 0;
		}
		int end = font.drawInBatch(text, x, y, color, shadow, pose.last().pose(), bufferSource, false, 0, 0xF000F0, font.isBidirectional());
		flush();
		return end;
	}

	public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
		return drawString(font, text, x, y, color, true);
	}

	public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		int end = font.drawInBatch(text, x, y, color, shadow, pose.last().pose(), bufferSource, false, 0, 0xF000F0);
		flush();
		return end;
	}

	public int drawString(Font font, Component text, int x, int y, int color) {
		return drawString(font, text, x, y, color, true);
	}

	public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
		return drawString(font, text.getVisualOrderText(), x, y, color, shadow);
	}

	public void drawWordWrap(Font font, FormattedText text, int x, int y, int width, int color) {
		for (FormattedCharSequence line : font.split(text, width)) {
			drawString(font, line, x, y, color, false);
			y += 9;
		}
	}

	public int drawStringWithBackdrop(Font font, Component text, int x, int y, int width, int color) {
		int background = minecraft.options.getBackgroundColor(0.0F);
		if (background != 0) {
			fill(x - 2, y - 2, x + width + 2, y + 9 + 2, ARGB.multiply(background, color));
		}
		return drawString(font, text, x, y, color, true);
	}

	// ---------- textures ----------

	public void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite) {
		RenderSystem.setShaderTexture(0, sprite.atlas().location());
		innerBlit(x, x + width, y, y + height, z, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1());
	}

	public void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite, float r, float g, float b, float a) {
		RenderSystem.setShaderColor(r, g, b, a);
		blit(x, y, z, width, height, sprite);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	public void blit(ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
		blit(texture, x, y, 0, u, v, width, height, 256, 256);
	}

	public void blit(ResourceLocation texture, int x, int y, int z, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		blit(texture, x, x + width, y, y + height, z, width, height, u, v, textureWidth, textureHeight);
	}

	public void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight, int textureWidth,
		int textureHeight) {
		blit(texture, x, x + width, y, y + height, 0, uWidth, vHeight, u, v, textureWidth, textureHeight);
	}

	public void blit(ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		blit(texture, x, y, width, height, u, v, width, height, textureWidth, textureHeight);
	}

	void blit(ResourceLocation texture, int x0, int x1, int y0, int y1, int z, int uWidth, int vHeight, float u, float v, int textureWidth,
		int textureHeight) {
		RenderSystem.setShaderTexture(0, texture);
		innerBlit(x0, x1, y0, y1, z, u / textureWidth, (u + uWidth) / textureWidth, v / textureHeight, (v + vHeight) / textureHeight);
	}

	/** One textured quad with the texture bound at unit 0 (1.19's innerBlit; blending is the caller's). */
	void innerBlit(float x0, float x1, float y0, float y1, float z, float u0, float u1, float v0, float v1) {
		Matrix4f matrix = pose.last().pose();
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		buffer.vertex(matrix, x0, y1, z).uv(u0, v1).endVertex();
		buffer.vertex(matrix, x1, y1, z).uv(u1, v1).endVertex();
		buffer.vertex(matrix, x1, y0, z).uv(u1, v0).endVertex();
		buffer.vertex(matrix, x0, y0, z).uv(u0, v0).endVertex();
		BufferUploader.drawWithShader(buffer.end());
	}

	/** A GUI sprite as 1.20.2+ names them (hud/heart/full...), from the sheet 1.19 keeps it in ({@link GuiSprites}). */
	public void blitSprite(ResourceLocation sprite, int x, int y, int width, int height) {
		GuiSprites.Region region = GuiSprites.region(sprite);
		if (region == null) {
			return;
		}
		RenderSystem.enableBlend();
		blit(region.texture(), x, x + width, y, y + height, 0, region.width(), region.height(), region.u(), region.v(), region.textureWidth(),
			region.textureHeight());
	}

	public void blitSprite(ResourceLocation sprite, int x, int y, int z, int width, int height) {
		blitSprite(sprite, x, y, width, height);
	}

	/** The part ({@code u}, {@code v}, {@code width} x {@code height}) of a {@code spriteWidth} x {@code spriteHeight} sprite. */
	public void blitSprite(ResourceLocation sprite, int spriteWidth, int spriteHeight, int u, int v, int x, int y, int width, int height) {
		GuiSprites.Region region = GuiSprites.region(sprite);
		if (region == null || width <= 0 || height <= 0) {
			return;
		}
		float su = region.width() / (float) spriteWidth;
		float sv = region.height() / (float) spriteHeight;
		RenderSystem.enableBlend();
		RenderSystem.setShaderTexture(0, region.texture());
		innerBlit(x, x + width, y, y + height, 0, (region.u() + u * su) / region.textureWidth(), (region.u() + (u + width) * su) / region.textureWidth(),
			(region.v() + v * sv) / region.textureHeight(), (region.v() + (v + height) * sv) / region.textureHeight());
	}

	public void blitSprite(ResourceLocation sprite, int spriteWidth, int spriteHeight, int u, int v, int x, int y, int z, int width, int height) {
		blitSprite(sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
	}

	// ---------- items: 1.19 draws GUI items through the model-view stack, so the pose goes on it while they draw ----------

	public void renderItem(ItemStack stack, int x, int y) {
		item(Minecraft.getInstance().player, stack, x, y, 0, 0, false);
	}

	public void renderItem(ItemStack stack, int x, int y, int seed) {
		item(Minecraft.getInstance().player, stack, x, y, seed, 0, false);
	}

	public void renderItem(ItemStack stack, int x, int y, int seed, int z) {
		item(Minecraft.getInstance().player, stack, x, y, seed, z, false);
	}

	public void renderFakeItem(ItemStack stack, int x, int y) {
		item(null, stack, x, y, 0, 0, true);
	}

	public void renderFakeItem(ItemStack stack, int x, int y, int seed) {
		item(null, stack, x, y, seed, 0, true);
	}

	public void renderItem(LivingEntity entity, ItemStack stack, int x, int y, int seed) {
		item(entity, stack, x, y, seed, 0, false);
	}

	private void item(@Nullable LivingEntity entity, ItemStack stack, int x, int y, int seed, int z, boolean fake) {
		if (stack.isEmpty()) {
			return;
		}
		ItemRenderer items = minecraft.getItemRenderer();
		withPose(() -> {
			if (fake) {
				items.renderAndDecorateFakeItem(stack, x, y);
			} else if (entity != null) {
				items.renderAndDecorateItem(entity, stack, x, y, seed);
			} else {
				items.renderAndDecorateItem(stack, x, y, seed, z);
			}
		});
	}

	public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
		renderItemDecorations(font, stack, x, y, null);
	}

	public void renderItemDecorations(Font font, ItemStack stack, int x, int y, @Nullable String count) {
		if (stack.isEmpty()) {
			return;
		}
		ItemRenderer items = minecraft.getItemRenderer();
		withPose(() -> items.renderGuiItemDecorations(font, stack, x, y, count));
	}

	/** Runs a 1.19 GUI draw that reads the model-view stack, with this GuiGraphics' pose on it. */
	public void withPose(Runnable draw) {
		flush();
		PoseStack modelView = RenderSystem.getModelViewStack();
		modelView.pushPose();
		modelView.mulPoseMatrix(pose.last().pose());
		RenderSystem.applyModelViewMatrix();
		try {
			draw.run();
		} finally {
			modelView.popPose();
			RenderSystem.applyModelViewMatrix();
		}
	}

	// ---------- tooltips ----------

	public void renderTooltip(Font font, ItemStack stack, int x, int y) {
		Screen screen = minecraft.screen;
		List<Component> lines = screen != null ? screen.getTooltipFromItem(stack)
			: stack.getTooltipLines(minecraft.player, minecraft.options.advancedItemTooltips ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
				: net.minecraft.world.item.TooltipFlag.Default.NORMAL);
		renderTooltip(font, lines, stack.getTooltipImage(), x, y);
	}

	public void renderTooltip(Font font, List<Component> lines, Optional<TooltipComponent> image, int x, int y) {
		List<ClientTooltipComponent> components = lines.stream().map(Component::getVisualOrderText).map(ClientTooltipComponent::create)
			.collect(Collectors.toList());
		image.ifPresent(i -> components.add(1, ClientTooltipComponent.create(i)));
		Tooltips.draw(this, font, components, x, y, DefaultTooltipPositioner.INSTANCE);
	}

	public void renderTooltip(Font font, Component text, int x, int y) {
		renderTooltip(font, List.of(text.getVisualOrderText()), x, y);
	}

	public void renderComponentTooltip(Font font, List<Component> lines, int x, int y) {
		renderTooltip(font, lines.stream().map(Component::getVisualOrderText).toList(), x, y);
	}

	public void renderTooltip(Font font, List<? extends FormattedCharSequence> lines, int x, int y) {
		Tooltips.draw(this, font, lines.stream().map(ClientTooltipComponent::create).collect(Collectors.toList()), x, y,
			DefaultTooltipPositioner.INSTANCE);
	}

	public void renderTooltip(Font font, List<FormattedCharSequence> lines, ClientTooltipPositioner positioner, int x, int y) {
		Tooltips.draw(this, font, lines.stream().map(ClientTooltipComponent::create).collect(Collectors.toList()), x, y, positioner);
	}

	public void renderComponentHoverEffect(Font font, @Nullable Style style, int x, int y) {
		Screen screen = minecraft.screen;
		if (screen != null && style != null && style.getHoverEvent() != null) {
			((com.flintclient.client.mixin.bridge.ScreenInvoker) screen).flint$renderComponentHoverEffect(pose, style, x, y);
		}
	}

	/** Mth's clamp for the bridge's callers that came from 1.20's code. */
	static int clampColor(int value) {
		return Mth.clamp(value, 0, 255);
	}

	/** 1.19's GuiComponent, for the few draws only it has (its blit offset is per object; Flint never sets one). */
	static final GuiComponent COMPONENT = new GuiComponent() {
	};
}
