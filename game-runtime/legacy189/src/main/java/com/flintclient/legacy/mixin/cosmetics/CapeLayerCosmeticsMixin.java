package com.flintclient.legacy.mixin.cosmetics;

import com.flintclient.legacy.cosmetics.Cosmetics189;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.entity.layer.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wings that ask to hide the cape hide it (as on 26.3). */
@Mixin(CapeLayer.class)
abstract class CapeLayerCosmeticsMixin {
	@Inject(method = "render(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;FFFFFFF)V", at = @At("HEAD"), cancellable = true)
	private void flint$hiddenByWings(ClientPlayerEntity player, float a, float b, float c, float d, float e, float f, float g, CallbackInfo ci) {
		if (Cosmetics189.hidesCape(player)) {
			ci.cancel();
		}
	}
}
