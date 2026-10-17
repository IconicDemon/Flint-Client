package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hurt Cam: Minecraft's damage tilt strength, scaled by the module while it is on. */
@Mixin(GameRenderer.class)
abstract class GameRendererFeatureMixin {
	@ModifyExpressionValue(method = "bobHurt", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;damageTiltStrength:D"))
	private double flint$hurtCam(double strength) {
		return Mc263Features.hurtTilt(strength);
	}
}
