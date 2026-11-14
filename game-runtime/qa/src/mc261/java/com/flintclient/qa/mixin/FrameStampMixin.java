package com.flintclient.qa.mixin;

import com.flintclient.qa.QaStamp;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** QaStamp on 26.1: the frame number painted into the finished picture before it goes to the window; the present timed. */
@Mixin(Minecraft.class)
abstract class FrameStampMixin {
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V"))
	private void flintqa$stamp(boolean advanceGameTime, CallbackInfo ci) {
		if (!QaStamp.active()) {
			return;
		}
		RenderTarget target = ((Minecraft) (Object) this).getMainRenderTarget();
		if (target.getColorTexture() == null || target.getDepthTexture() == null) {
			return;
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		int size = Math.min(QaStamp.BLOCK, Math.max(4, target.width / (QaStamp.BLOCKS + 2)));
		for (int i = 0; i < QaStamp.BLOCKS; i++) {
			encoder.clearColorAndDepthTextures(target.getColorTexture(), QaStamp.white(i) ? 0xFFFFFFFF : 0xFF000000, target.getDepthTexture(), 1.0,
				i * size, 0, size, size);
		}
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
	private void flintqa$beforePresent(boolean advanceGameTime, CallbackInfo ci) {
		QaStamp.beforePresent();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V", shift = At.Shift.AFTER))
	private void flintqa$afterPresent(boolean advanceGameTime, CallbackInfo ci) {
		QaStamp.afterPresent();
	}
}
