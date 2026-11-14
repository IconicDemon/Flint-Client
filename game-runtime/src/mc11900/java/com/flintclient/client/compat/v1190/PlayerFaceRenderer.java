package com.flintclient.client.compat.v1190;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;

/**
 * 1.19.1's PlayerFaceRenderer on Minecraft 1.19 (1.19.0), which has none (the rules point the shared sources here): a player's
 * face (and hat) from the skin texture already set as shader texture 0, drawn as 1.19.1 draws it.
 */
public final class PlayerFaceRenderer {
	private PlayerFaceRenderer() {
	}

	public static void draw(PoseStack pose, int x, int y, int size) {
		draw(pose, x, y, size, true, false);
	}

	public static void draw(PoseStack pose, int x, int y, int size, boolean hat, boolean upsideDown) {
		int v = 8 + (upsideDown ? 8 : 0);
		int height = 8 * (upsideDown ? -1 : 1);
		GuiComponent.blit(pose, x, y, size, size, 8.0F, v, 8, height, 64, 64);
		if (hat) {
			RenderSystem.enableBlend();
			GuiComponent.blit(pose, x, y, size, size, 40.0F, v, 8, height, 64, 64);
			RenderSystem.disableBlend();
		}
	}
}
