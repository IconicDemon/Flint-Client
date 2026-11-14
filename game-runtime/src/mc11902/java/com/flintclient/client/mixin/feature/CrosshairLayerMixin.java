package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.platform.Mc263Crosshair;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crosshair (Custom Crosshair Mod) on Minecraft 1.19.2: drawn once the whole HUD is drawn, so it sits over every HUD piece
 * (Minecraft's own crosshair method is skipped, HudFeatureMixin). Its Debug style is Minecraft's 3D crosshair, drawn as
 * 1.19.2's renderCrosshair draws it under the debug screen (on the model-view pose, at the screen's middle). F1 hides it
 * with the rest of the HUD.
 */
@Mixin(Gui.class)
abstract class CrosshairLayerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private int screenWidth;
	@Shadow
	private int screenHeight;

	@Inject(method = "render", at = @At("RETURN"))
	private void flint$crosshair(PoseStack pose, float partialTick, CallbackInfo callback) {
		if (!Mc263Crosshair.replacesVanilla() || minecraft.options.hideGui) {
			return;
		}
		Mc263Crosshair.afterGui(GuiGraphics.of(pose));
		if (Mc263Crosshair.debugStyleThisFrame()) {
			Camera camera = minecraft.gameRenderer.getMainCamera();
			PoseStack view = RenderSystem.getModelViewStack();
			view.pushPose();
			view.translate(screenWidth / 2, screenHeight / 2, 0.0);
			view.mulPose(Vector3f.XN.rotationDegrees(camera.getXRot()));
			view.mulPose(Vector3f.YP.rotationDegrees(camera.getYRot()));
			view.scale(-1.0F, -1.0F, -1.0F);
			RenderSystem.applyModelViewMatrix();
			RenderSystem.renderCrosshair(10);
			view.popPose();
			RenderSystem.applyModelViewMatrix();
		}
	}
}
