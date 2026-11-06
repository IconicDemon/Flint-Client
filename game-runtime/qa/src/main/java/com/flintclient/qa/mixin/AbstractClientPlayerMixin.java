package com.flintclient.qa.mixin;

import com.flintclient.qa.QaSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The QA player's skin and cape as a tour set them ({@link QaSkin}); everyone else's as Minecraft has them. */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Unique
	private static boolean flintqa$broken;

	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void flintqa$skin(CallbackInfoReturnable<PlayerSkin> callback) {
		if (!flintqa$broken && (Object) this == Minecraft.getInstance().player) {
			try {
				callback.setReturnValue(QaSkin.apply(callback.getReturnValue()));
			} catch (LinkageError e) {
				// A newer Minecraft than this QA build (a forward-compatibility run): the default skin.
				flintqa$broken = true;
			}
		}
	}
}
