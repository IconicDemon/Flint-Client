package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Crosshair;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crosshair (Custom Crosshair Mod): drawn once the whole GUI is extracted, just before it is drawn, so it sits over every
 * HUD piece; its Debug style is Minecraft's 3D crosshair, and Minecraft's own is left out under the debug screen unless kept.
 * Minecraft 26.2's 3D crosshair renderer draws into the bound target itself.
 */
@Mixin(GameRenderer.class)
abstract class CrosshairLayerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private GameRenderState gameRenderState;
	@Shadow
	@Final
	private DebugCrosshairRenderer debugCrosshairRenderer;

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void flint$crosshair(CallbackInfo callback) {
		if (!Mc263Crosshair.replacesVanilla()) {
			return;
		}
		Minecraft mc = minecraft;
		Mc263Crosshair.afterGui(new GuiGraphicsExtractor(mc, gameRenderState.guiRenderState, (int) mc.mouseHandler.getScaledXPos(mc.getWindow()),
			(int) mc.mouseHandler.getScaledYPos(mc.getWindow())));
		if (Mc263Crosshair.debugStyleThisFrame()) {
			debugCrosshairRenderer.render(gameRenderState.levelRenderState.cameraRenderState, gameRenderState.windowRenderState.guiScale);
		}
	}

	@WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/DebugCrosshairRenderer;render(Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V"))
	private boolean flint$vanilla3d(DebugCrosshairRenderer renderer, CameraRenderState camera, int scale) {
		return Mc263Crosshair.vanilla3dCrosshair();
	}
}
