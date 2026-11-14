package com.flintclient.qa.mixin;

import com.flintclient.qa.QaStamp;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QaStamp on 26.3: the frame number painted into the finished picture (the main render target, with the GUI on it) just
 * before it is copied to the window, by region clears (any backend), and the present call timed. Dev only.
 */
@Mixin(Minecraft.class)
abstract class FrameStampMixin {
	private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
	private static final Vector4f BLACK = new Vector4f(0, 0, 0, 1);

	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
	private void flintqa$stamp(boolean advanceGameTime, CallbackInfo ci) {
		if (!QaStamp.active()) {
			return;
		}
		RenderTarget target = ((Minecraft) (Object) this).gameRenderer.mainRenderTarget();
		if (target.getColorTexture() == null || target.getDepthTexture() == null) {
			return;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		int size = Math.min(QaStamp.BLOCK, Math.max(4, target.width / (QaStamp.BLOCKS + 2)));
		for (int i = 0; i < QaStamp.BLOCKS; i++) {
			encoder.clearColorAndDepthTextures(target.getColorTexture(), QaStamp.white(i) ? WHITE : BLACK, target.getDepthTexture(), 1.0,
				i * size, 0, size, size, 0);
		}
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void flintqa$beforePresent(boolean advanceGameTime, CallbackInfo ci) {
		QaStamp.beforePresent();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
	private void flintqa$afterPresent(boolean advanceGameTime, CallbackInfo ci) {
		QaStamp.afterPresent();
	}
}
