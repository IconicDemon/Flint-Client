package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FOV Changer: Minecraft's FOV effects (flying, speed, bow, spyglass), each scaled by the module's percentage. */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
	private void flint$fovChanger(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> callback) {
		float modifier = Mc263Features.fovModifier((AbstractClientPlayer) (Object) this, firstPerson, effectScale);
		if (!Float.isNaN(modifier)) {
			callback.setReturnValue(modifier);
		}
	}
}
