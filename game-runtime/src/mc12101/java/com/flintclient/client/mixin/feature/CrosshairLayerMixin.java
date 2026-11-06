package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Crosshair;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crosshair (Custom Crosshair Mod) on Minecraft 1.21.1: drawn once the whole HUD is drawn, so it sits over every HUD piece
 * (Minecraft's own crosshair method is skipped, HudFeatureMixin). Its Debug style is Minecraft's 3D crosshair, drawn as
 * 1.21.1's renderCrosshair draws it under the debug screen. F1 hides it with the rest of the HUD.
 */
@Mixin(Gui.class)
abstract class CrosshairLayerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "render", at = @At("RETURN"))
	private void flint$crosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		if (!Mc263Crosshair.replacesVanilla() || minecraft.options.hideGui) {
			return;
		}
		Mc263Crosshair.afterGui(graphics);
		if (Mc263Crosshair.debugStyleThisFrame()) {
			Camera camera = minecraft.gameRenderer.getMainCamera();
			Matrix4fStack stack = RenderSystem.getModelViewStack();
			stack.pushMatrix();
			stack.mul(graphics.pose().last().pose());
			stack.translate(graphics.guiWidth() / 2, graphics.guiHeight() / 2, 0.0F);
			stack.rotateX(-camera.getXRot() * (float) (Math.PI / 180.0));
			stack.rotateY(camera.getYRot() * (float) (Math.PI / 180.0));
			stack.scale(-1.0F, -1.0F, -1.0F);
			RenderSystem.applyModelViewMatrix();
			RenderSystem.renderCrosshair(10);
			stack.popMatrix();
			RenderSystem.applyModelViewMatrix();
		}
	}
}
