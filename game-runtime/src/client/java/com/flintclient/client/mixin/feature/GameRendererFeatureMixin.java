package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.platform.Mc263HurtCam;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hurt Cam (BetterHurtCam's behaviour): bobHurt's turns times the multiplier, skipped when off, one direction for the Old
 * type. Minimal view bobbing (a game option): the world's camera skips its walking bob, while the hand (bobbed in
 * renderItemInHand) keeps it.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererFeatureMixin {
	/** Animations' Disable hit direction and Hurt Cam's Old type: the hurt tilt always leans the same way. */
	@ModifyExpressionValue(method = "bobHurt", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/renderer/state/level/CameraEntityRenderState;hurtDir:F"))
	private float flint$hitDirection(float direction) {
		return Mc263HurtCam.hurtDir(direction);
	}

	/** Hurt Cam: Hurtcam enabled off skips the hurt camera and the death roll. */
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void flint$hurtCamOff(CallbackInfo callback) {
		if (!Mc263HurtCam.bobHurt()) {
			callback.cancel();
		}
	}

	/** Hurt Cam: each of bobHurt's four turns times the multiplier (the call that makes a turn differs: McCompat.HURT_TURN). */
	@ModifyArg(method = "bobHurt", index = McCompat.HURT_TURN_ANGLE, at = @At(value = "INVOKE", target = McCompat.HURT_TURN))
	private float flint$hurtCamTurn(float degrees) {
		return Mc263HurtCam.angle(degrees);
	}

	@WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private boolean flint$cameraBob(GameRenderer renderer, CameraRenderState camera, PoseStack pose) {
		return !ControlState.settings().gameBool(GameOptions.MINIMAL_BOBBING);
	}
}
