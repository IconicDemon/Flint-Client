package com.flintclient.qa.legacy.mixin;

import com.flintclient.qa.legacy.QaCape;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The QA player's Minecraft cape stand-in ({@link QaCape}), where Minecraft itself keeps a player's cape. */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
	@Inject(method = "getCapeTexture", at = @At("RETURN"), cancellable = true)
	private void flintqa$minecraftCape(CallbackInfoReturnable<Identifier> callback) {
		Identifier cape = QaCape.minecraft((PlayerInfo) (Object) this, callback.getReturnValue());
		if (cape != callback.getReturnValue()) {
			callback.setReturnValue(cape);
		}
	}
}
