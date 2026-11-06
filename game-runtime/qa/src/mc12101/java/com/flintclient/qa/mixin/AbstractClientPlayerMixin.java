package com.flintclient.qa.mixin;

import com.flintclient.qa.QaSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The QA player's skin and cape as a tour set them ({@link QaSkin}); everyone else's as Minecraft has them (1.21.1). */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void flintqa$skin(CallbackInfoReturnable<PlayerSkin> callback) {
		if ((Object) this == Minecraft.getInstance().player) {
			callback.setReturnValue(QaSkin.apply(callback.getReturnValue()));
		}
	}
}
