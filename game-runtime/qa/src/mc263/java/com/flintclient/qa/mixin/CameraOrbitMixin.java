package com.flintclient.qa.mixin;

import com.flintclient.qa.QaCapes;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The third-person camera turned round the player by {@code QaCapes.orbit DEGREES} (90: from the side), right before it backs
 * away from the player, so a tour can film a cape's profile while the player walks. Dev only (26.3).
 */
@Mixin(Camera.class)
abstract class CameraOrbitMixin {
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;move(FFF)V"), require = 0)
	private void flintqa$orbit(float partialTicks, CallbackInfo ci) {
		if (QaCapes.orbit != 0) {
			setRotation(yRot + QaCapes.orbit, xRot);
		}
	}
}
