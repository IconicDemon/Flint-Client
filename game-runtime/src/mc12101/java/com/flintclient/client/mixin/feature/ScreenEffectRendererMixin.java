package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlay on Minecraft 1.21.1: the first-person fire lowered or hidden, and the underwater tint hidden. 1.21.1 draws both
 * straight away from static methods taking the game and the view's pose (the totem is GameRenderer's, TotemPopMixin).
 */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
	@Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
	private static void flint$fireStart(Minecraft minecraft, PoseStack poseStack, CallbackInfo callback) {
		int fire = Mc263Visuals.fire();
		if (fire == 2) {
			callback.cancel();
		} else if (fire == 1) {
			poseStack.pushPose();
			poseStack.translate(0.0F, -Mc263Visuals.lowFireOffset(), 0.0F);
		}
	}

	@Inject(method = "renderFire", at = @At("RETURN"))
	private static void flint$fireEnd(Minecraft minecraft, PoseStack poseStack, CallbackInfo callback) {
		if (Mc263Visuals.fire() == 1) {
			poseStack.popPose();
		}
	}

	@Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
	private static void flint$water(Minecraft minecraft, PoseStack poseStack, CallbackInfo callback) {
		if (Mc263Visuals.hideUnderwater()) {
			callback.cancel();
		}
	}
}
