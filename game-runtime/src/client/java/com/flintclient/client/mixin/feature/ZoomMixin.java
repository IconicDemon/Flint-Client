package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Zoom;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom's relative view bobbing (Zoomify): the walking bob, calmer the further you zoom. */
@Mixin(targets = McCompat.ZOOM_BOB_CLASS)
abstract class ZoomMixin {
	@ModifyExpressionValue(method = McCompat.ZOOM_BOB_METHOD, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/entity/ClientAvatarState;getInterpolatedBob(F)F"))
	private float flint$relativeBob(float bob) {
		return Mc263Zoom.bob(bob);
	}
}
