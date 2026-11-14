package com.flintclient.client.compat.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * What Flint's HUD mixins share about the parts Minecraft 1.19.2 draws inline in Gui.renderPlayerHealth (1.20.2 and later
 * draw each in its own method: renderArmor, renderFood and the air row): the heart rows Minecraft laid out this frame, how
 * Autohide HUD and the Hotbar mod are drawing each part (mixin.feature.HudAutoHideMixin), and whether Flint drew the armor
 * row itself (mixin.feature.HudBarsMixin). Also puts back the GUI state 1.19.2 keeps across those draws: it binds icons.png
 * once and blits from it with the colour and blend it set, so after Flint draws in the middle of them they are restored.
 * Render thread only; nothing is allocated.
 */
public final class HudParts {
	/** How a part is drawn: left out, as it is, or moved (the model-view pose is popped after). */
	public static final int SKIP = 0;
	public static final int PLAIN = 1;
	public static final int MOVED = 2;

	/** The heart rows and their spacing Minecraft laid out this frame (after Hearts' own row count). */
	public static int heartRows = 1;
	public static int rowHeight = 10;

	/** How the armor row, the food row and the air row are being drawn. */
	public static int armor = PLAIN;
	public static int food = PLAIN;
	public static int air = PLAIN;

	/** Whether Flint drew the armor row this frame (Minecraft's is then left out). */
	public static boolean flintArmor;

	private HudParts() {
	}

	/**
	 * Starts drawing a HUD part moved: the model-view pose (which 1.19.2's GUI shader, text and items all draw through, so
	 * the part's items move with it) translated by ({@code x}, {@code y}) and scaled by {@code scale} around
	 * ({@code anchorX}, {@code anchorY}). Ended by {@link #endMoved}.
	 */
	public static void beginMoved(float x, float y, float scale, float anchorX, float anchorY) {
		PoseStack view = RenderSystem.getModelViewStack();
		view.pushPose();
		view.translate(x + anchorX, y + anchorY, 0.0);
		if (scale != 1.0F) {
			view.scale(scale, scale, 1.0F);
		}
		view.translate(-anchorX, -anchorY, 0.0);
		RenderSystem.applyModelViewMatrix();
	}

	public static void endMoved() {
		RenderSystem.getModelViewStack().popPose();
		RenderSystem.applyModelViewMatrix();
	}

	/** After Flint drew among Minecraft's HUD icons: icons.png bound again, white, blended as Gui.render left it. */
	public static void icons() {
		restore(GuiComponent.GUI_ICONS_LOCATION);
	}

	/** After Flint drew among Minecraft's blits from {@code texture}: it is bound again, white, blended as Minecraft set it. */
	public static void restore(ResourceLocation texture) {
		RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
	}
}
