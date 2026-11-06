package com.flintclient.client.mixin;

import com.flintclient.client.compat.GraphicsBridge;
import com.flintclient.client.compat.Pose2D;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Minecraft 1.21.1: GuiGraphics answers 26.x's GuiGraphicsExtractor methods ({@link GraphicsBridge}) and keeps the 2D view of
 * its pose stack the shared sources draw through.
 */
@Mixin(GuiGraphics.class)
abstract class GraphicsBridgeMixin implements GraphicsBridge {
	@Shadow
	@Final
	private PoseStack pose;
	@Unique
	private Pose2D flint$pose2d;

	@Override
	public Pose2D pose2d() {
		Pose2D view = flint$pose2d;
		if (view == null) {
			view = new Pose2D(pose);
			flint$pose2d = view;
		}
		return view.sync();
	}
}
