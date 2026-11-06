package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Crosshair;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crosshair (Custom Crosshair Mod): drawn once the whole GUI is extracted, just before it is drawn, so it sits over every
 * HUD piece; its Debug style is Minecraft's 3D crosshair, and Minecraft's own is left out under the debug screen unless kept.
 * Minecraft 1.21.11 builds the GUI and draws the frame in one render method.
 */
@Mixin(GameRenderer.class)
abstract class CrosshairLayerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private Camera mainCamera;
	@Shadow
	@Final
	GuiRenderState guiRenderState;

	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void flint$crosshair(CallbackInfo callback) {
		if (!Mc263Crosshair.replacesVanilla()) {
			return;
		}
		Minecraft mc = minecraft;
		Mc263Crosshair.afterGui(new GuiGraphics(mc, guiRenderState, (int) mc.mouseHandler.getScaledXPos(mc.getWindow()),
			(int) mc.mouseHandler.getScaledYPos(mc.getWindow())));
		if (Mc263Crosshair.debugStyleThisFrame()) {
			mc.getDebugOverlay().render3dCrosshair(mainCamera);
		}
	}

	@WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;render3dCrosshair(Lnet/minecraft/client/Camera;)V"))
	private boolean flint$vanilla3d(DebugScreenOverlay overlay, Camera camera) {
		return Mc263Crosshair.vanilla3dCrosshair();
	}
}
