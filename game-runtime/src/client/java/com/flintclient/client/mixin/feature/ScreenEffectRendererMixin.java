package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overlay: the first-person fire lowered or hidden, and the underwater tint hidden. */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void flint$fireStart(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo callback) {
		int fire = Mc263Visuals.fire();
		if (fire == 2) {
			callback.cancel();
		} else if (fire == 1) {
			poseStack.pushPose();
			poseStack.translate(0.0F, -Mc263Visuals.lowFireOffset(), 0.0F);
		}
	}

	@Inject(method = "submitFire", at = @At("RETURN"))
	private static void flint$fireEnd(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo callback) {
		if (Mc263Visuals.fire() == 1) {
			poseStack.popPose();
		}
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void flint$water(PlayerRenderState.WaterOverlay overlay, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo callback) {
		if (Mc263Visuals.hideUnderwater()) {
			callback.cancel();
		}
	}
}
