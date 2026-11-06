package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.ColorGrade;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Color Saturation's grading pass ({@link ColorGrade}), once per world frame: in {@code GameRenderer.render}, right after
 * Minecraft's own post effects and before the GUI. Minecraft 26.2 runs its post chain inline, so the pass goes just
 * before the fog's end of frame, on frames that drew the world (Minecraft's own condition for it). require = 0: if
 * another mod takes over that call, the game still starts and the effect simply never runs.
 */
@Mixin(GameRenderer.class)
abstract class ColorGradeMixin {
	@Shadow
	public abstract com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget();

	@Inject(method = "render", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void flint$colorGrade(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
		Minecraft mc = Minecraft.getInstance();
		if (renderLevel && mc.level != null && mc.isGameLoadFinished()) {
			ColorGrade.afterWorld(mainRenderTarget());
		}
	}
}
