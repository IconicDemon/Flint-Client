package com.flintclient.client.compat.gui;

import com.flintclient.client.compat.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * A GuiGraphics over Minecraft 1.19.2's model-view pose, for moving a whole screen's drawing: 1.19.2 draws a container's
 * items through the model-view pose (RenderSystem.getModelViewStack), not the GUI PoseStack its other drawing takes, and its
 * GUI shader, text and fills apply the model-view pose too, so moving it moves everything (Inventory's open and close
 * animations: mixin.feature.ScreenContainerAnimationMixin). After changing it, {@link #apply} hands it to the shaders.
 * Render thread only.
 */
public final class ModelView {
	private static @Nullable GuiGraphics graphics;

	private ModelView() {
	}

	/** The GuiGraphics whose pose is the model-view pose (Minecraft keeps one model-view stack). */
	public static GuiGraphics graphics() {
		GuiGraphics g = graphics;
		if (g == null || g.pose() != RenderSystem.getModelViewStack()) {
			Minecraft mc = Minecraft.getInstance();
			g = new GuiGraphics(mc, RenderSystem.getModelViewStack(), mc.renderBuffers().bufferSource());
			graphics = g;
		}
		return g;
	}

	/** The model-view pose as it is now, handed to the shaders. */
	public static void apply() {
		RenderSystem.applyModelViewMatrix();
	}
}
