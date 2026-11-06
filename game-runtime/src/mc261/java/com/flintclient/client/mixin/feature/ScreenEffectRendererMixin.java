package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overlay: the first-person fire lowered or hidden, and the underwater tint hidden. */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
	@Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
	private static void flint$fireStart(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo callback) {
		int fire = Mc263Visuals.fire();
		if (fire == 2) {
			callback.cancel();
		} else if (fire == 1) {
			poseStack.pushPose();
			poseStack.translate(0.0F, -Mc263Visuals.lowFireOffset(), 0.0F);
		}
	}

	@Inject(method = "renderFire", at = @At("RETURN"))
	private static void flint$fireEnd(PoseStack poseStack, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo callback) {
		if (Mc263Visuals.fire() == 1) {
			poseStack.popPose();
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static boolean flint$popMoved;

	/** Totem: the big totem on screen at its own size and place. */
	@Inject(method = "renderItemActivationAnimation", at = @At("HEAD"))
	private void flint$popStart(PoseStack poseStack, float partialTick, SubmitNodeCollector collector, CallbackInfo callback) {
		flint$popMoved = com.flintclient.client.control.platform.Mc263MoreMods.popStart(poseStack);
	}

	@Inject(method = "renderItemActivationAnimation", at = @At("RETURN"))
	private void flint$popEnd(PoseStack poseStack, float partialTick, SubmitNodeCollector collector, CallbackInfo callback) {
		if (flint$popMoved) {
			flint$popMoved = false;
			com.flintclient.client.control.platform.Mc263MoreMods.popEnd(poseStack);
		}
	}

	@Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
	private static void flint$water(net.minecraft.client.Minecraft minecraft, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo callback) {
		if (Mc263Visuals.hideUnderwater()) {
			callback.cancel();
		}
	}
}
